package com.custom.watchnotes.companion;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Thread-safe JSON store for notes.json inside app internal storage.
 */
public final class NoteStore {
    private static final String FILE_NAME = "notes.json";
    private static final Object LOCK = new Object();

    public static final class Note {
        public final int id;
        public final String title;
        public final String body;
        public final String createdAt;

        public Note(int id, String title, String body, String createdAt) {
            this.id = id;
            this.title = title;
            this.body = body;
            this.createdAt = createdAt;
        }

        public JSONObject toJson() throws JSONException {
            JSONObject obj = new JSONObject();
            obj.put("id", id);
            obj.put("title", title);
            obj.put("body", body);
            obj.put("created_at", createdAt);
            return obj;
        }
    }

    private NoteStore() {}

    public static List<Note> getNotes(Context context) {
        synchronized (LOCK) {
            ensureInitialized(context);
            return readNotesLocked(context);
        }
    }

    public static byte[] getRawJsonBytes(Context context) {
        synchronized (LOCK) {
            ensureInitialized(context);
            File file = new File(context.getFilesDir(), FILE_NAME);
            try {
                return readFileBytes(file);
            } catch (IOException e) {
                return "[]\n".getBytes(StandardCharsets.UTF_8);
            }
        }
    }

    public static Note addNote(Context context, String rawTitle, String rawBody) {
        synchronized (LOCK) {
            ensureInitialized(context);
            List<Note> notes = readNotesLocked(context);
            int maxId = 0;
            for (Note n : notes) {
                if (n.id > maxId) {
                    maxId = n.id;
                }
            }
            String body = rawBody == null ? "" : rawBody.trim();
            String title = normalizeTitle(rawTitle, body);
            Note created = new Note(maxId + 1, title, body, nowIsoUtc());
            notes.add(0, created);
            writeNotesLocked(context, notes);
            return created;
        }
    }

    public static void restoreNote(Context context, Note note, int index) {
        if (note == null) return;
        synchronized (LOCK) {
            ensureInitialized(context);
            List<Note> notes = readNotesLocked(context);
            int safeIndex = Math.max(0, Math.min(index, notes.size()));
            notes.add(safeIndex, note);
            writeNotesLocked(context, notes);
        }
    }

    public static boolean updateNote(Context context, int id, String rawTitle, String rawBody) {
        synchronized (LOCK) {
            ensureInitialized(context);
            List<Note> notes = readNotesLocked(context);
            String body = rawBody == null ? "" : rawBody.trim();
            String title = normalizeTitle(rawTitle, body);
            for (int i = 0; i < notes.size(); i++) {
                Note existing = notes.get(i);
                if (existing.id == id) {
                    notes.set(i, new Note(id, title, body, existing.createdAt));
                    writeNotesLocked(context, notes);
                    return true;
                }
            }
            return false;
        }
    }

    public static int deleteNote(Context context, int id) {
        synchronized (LOCK) {
            ensureInitialized(context);
            List<Note> notes = readNotesLocked(context);
            for (int i = 0; i < notes.size(); i++) {
                if (notes.get(i).id == id) {
                    notes.remove(i);
                    writeNotesLocked(context, notes);
                    return i;
                }
            }
            return -1;
        }
    }

    public static void clearNotes(Context context) {
        synchronized (LOCK) {
            writeNotesLocked(context, new ArrayList<Note>());
        }
    }

    private static String normalizeTitle(String rawTitle, String body) {
        String trimmed = rawTitle == null ? "" : rawTitle.trim();
        if (!trimmed.isEmpty()) {
            return trimmed;
        }
        if (body != null && !body.isEmpty()) {
            String[] lines = body.replace("\r\n", "\n").split("\n");
            for (String line : lines) {
                String candidate = line.trim();
                if (!candidate.isEmpty()) {
                    if (candidate.length() > 32) {
                        return candidate.substring(0, 29).trim() + "...";
                    }
                    return candidate;
                }
            }
        }
        return "Untitled";
    }

    private static void ensureInitialized(Context context) {
        File file = new File(context.getFilesDir(), FILE_NAME);
        if (file.exists()) {
            return;
        }
        List<Note> defaults = new ArrayList<>();
        String ts = nowIsoUtc();
        defaults.add(new Note(
                1,
                "Welcome to Watch Notes",
                "Paste any text in the phone app, tap Add Note, then tap Sync Notes on your Watch S5.\n\nNotes stay cached on the watch for offline reading.",
                ts));
        defaults.add(new Note(
                2,
                "Quick Tips",
                "You can also Share text from any Android app directly to Watch Notes.\n\nTap any note on your phone to edit it, or tap Delete to remove it.",
                ts));
        writeNotesLocked(context, defaults);
    }

    private static List<Note> readNotesLocked(Context context) {
        File file = new File(context.getFilesDir(), FILE_NAME);
        List<Note> result = new ArrayList<>();
        try {
            String raw = new String(readFileBytes(file), StandardCharsets.UTF_8).trim();
            if (raw.isEmpty()) {
                return result;
            }
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj == null) continue;
                result.add(new Note(
                        obj.optInt("id", i + 1),
                        obj.optString("title", "Untitled"),
                        obj.optString("body", ""),
                        obj.optString("created_at", "")));
            }
        } catch (IOException | JSONException ignored) {
            // Return empty list if file is corrupted or unreadable.
        }
        return result;
    }

    private static void writeNotesLocked(Context context, List<Note> notes) {
        JSONArray arr = new JSONArray();
        for (Note n : notes) {
            try {
                arr.put(n.toJson());
            } catch (JSONException ignored) {
            }
        }
        File target = new File(context.getFilesDir(), FILE_NAME);
        File tmp = new File(context.getFilesDir(), FILE_NAME + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            String json = arr.toString(2) + "\n";
            fos.write(json.getBytes(StandardCharsets.UTF_8));
            fos.getFD().sync();
        } catch (IOException | JSONException e) {
            return;
        }
        if (!tmp.renameTo(target)) {
            target.delete();
            tmp.renameTo(target);
        }
    }

    private static byte[] readFileBytes(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = fis.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }

    private static String nowIsoUtc() {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        return fmt.format(new Date());
    }
}
