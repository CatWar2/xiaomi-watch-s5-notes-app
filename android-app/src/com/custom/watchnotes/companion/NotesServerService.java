package com.custom.watchnotes.companion;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground service hosting an HTTP/1.1 server on port 8765 so Watch Notes on
 * the Xiaomi Watch S5 can sync notes over Mi Fitness / loopback.
 */
public class NotesServerService extends Service {
    public static final int PORT = 8765;
    public static final String ACTION_STATE_CHANGED = "com.custom.watchnotes.companion.STATE_CHANGED";
    public static final String ACTION_STOP = "com.custom.watchnotes.companion.STOP";

    private static final String CHANNEL_ID = "watch_notes_server";
    private static final int NOTIF_ID = 1001;

    private static volatile boolean running = false;
    private static volatile long lastSyncMillis = 0L;
    private static volatile int syncCount = 0;
    private static volatile String lastError = "";

    private ServerSocket serverSocket;
    private Thread acceptThread;
    private ExecutorService requestPool;

    public static boolean isRunning() {
        return running;
    }

    public static long getLastSyncMillis() {
        return lastSyncMillis;
    }

    public static int getSyncCount() {
        return syncCount;
    }

    public static String getLastError() {
        return lastError;
    }

    public static void ensureStarted(Context context) {
        Intent intent = new Intent(context, NotesServerService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (Exception e) {
            try {
                context.startService(intent);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        requestPool = Executors.newCachedThreadPool();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopServer();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        promoteToForeground();
        startServerIfNeeded();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopServer();
        if (requestPool != null) {
            requestPool.shutdownNow();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void promoteToForeground() {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentPending = PendingIntent.getActivity(
                this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent(this, NotesServerService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(
                this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        Notification notif = builder
                .setContentTitle("Watch Notes Sync Active")
                .setContentText("Serving notes on 127.0.0.1:" + PORT)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(contentPending)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        0, "Stop", stopPending).build())
                .build();

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIF_ID, notif);
            }
        } catch (Exception e) {
            lastError = e.getMessage() != null ? e.getMessage() : "Foreground restricted";
            notifyStateChanged();
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Watch Notes Sync Server",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Keeps the local HTTP server running for Xiaomi Watch S5 sync");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private synchronized void startServerIfNeeded() {
        if (running && serverSocket != null && !serverSocket.isClosed()) {
            notifyStateChanged();
            return;
        }
        stopServer();

        lastError = "";
        try {
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(InetAddress.getByName("0.0.0.0"), PORT));
            serverSocket = ss;
            running = true;
            notifyStateChanged();

            acceptThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    acceptLoop(ss);
                }
            }, "WatchNotesHttpAccept");
            acceptThread.start();
        } catch (IOException e) {
            running = false;
            lastError = e.getMessage() != null ? e.getMessage() : "Bind failed";
            notifyStateChanged();
        }
    }

    private synchronized void stopServer() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
            serverSocket = null;
        }
        if (acceptThread != null) {
            acceptThread.interrupt();
            acceptThread = null;
        }
        notifyStateChanged();
    }

    private void acceptLoop(ServerSocket ss) {
        while (running && !ss.isClosed()) {
            try {
                final Socket client = ss.accept();
                requestPool.execute(new Runnable() {
                    @Override
                    public void run() {
                        handleClient(client);
                    }
                });
            } catch (IOException e) {
                if (running) {
                    lastError = e.getMessage() != null ? e.getMessage() : "Accept error";
                    running = false;
                    notifyStateChanged();
                }
                break;
            }
        }
    }

    private void handleClient(Socket client) {
        try {
            client.setSoTimeout(5000);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII));
            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.isEmpty()) {
                return;
            }
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                // Drain HTTP headers before replying.
            }

            String[] parts = requestLine.split(" ");
            String method = parts.length > 0 ? parts[0] : "GET";
            String rawPath = parts.length > 1 ? parts[1] : "/";
            String path = rawPath.split("\\?", 2)[0];

            OutputStream out = client.getOutputStream();
            if ("OPTIONS".equalsIgnoreCase(method)) {
                writeResponse(out, "204 No Content", "text/plain; charset=utf-8", new byte[0]);
                return;
            }

            if ("GET".equalsIgnoreCase(method) && ("/notes.json".equals(path) || "/".equals(path))) {
                byte[] body = NoteStore.getRawJsonBytes(this);
                writeResponse(out, "200 OK", "application/json; charset=utf-8", body);
                lastSyncMillis = System.currentTimeMillis();
                syncCount++;
                notifyStateChanged();
            } else {
                byte[] body = "{\"error\":\"Not found\"}\n".getBytes(StandardCharsets.UTF_8);
                writeResponse(out, "404 Not Found", "application/json; charset=utf-8", body);
            }
        } catch (IOException ignored) {
        } finally {
            try {
                client.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void writeResponse(OutputStream out, String status, String contentType, byte[] body)
            throws IOException {
        String headers = "HTTP/1.1 " + status + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Connection: close\r\n"
                + "\r\n";
        out.write(headers.getBytes(StandardCharsets.US_ASCII));
        if (body.length > 0) {
            out.write(body);
        }
        out.flush();
    }

    private void notifyStateChanged() {
        Intent intent = new Intent(ACTION_STATE_CHANGED);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }
}
