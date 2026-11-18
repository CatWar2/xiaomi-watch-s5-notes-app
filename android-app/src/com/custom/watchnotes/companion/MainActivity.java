package com.custom.watchnotes.companion;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Companion phone UI for Watch Notes: paste/add, edit, delete notes and keep
 * the local HTTP server active for Xiaomi Watch S5 sync.
 */
public class MainActivity extends Activity {
    private static final int REQ_NOTIF = 101;

    private TextView statusTitleView;
    private TextView statusSubView;
    private Button serverToggleBtn;

    private EditText titleInput;
    private EditText bodyInput;

    private LinearLayout undoBar;
    private TextView undoLabel;
    private NoteStore.Note lastDeletedNote;
    private int lastDeletedIndex = -1;

    private TextView listHeaderView;
    private Button clearAllBtn;
    private LinearLayout notesContainer;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateServerBanner();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        requestNotificationPermissionIfNeeded();
        NotesServerService.ensureStarted(this);
        handleIncomingIntent(getIntent());
        refreshNotesList();
        updateServerBanner();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(NotesServerService.ACTION_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, filter);
        }
        NotesServerService.ensureStarted(this);
        refreshNotesList();
        updateServerBanner();
    }

    @Override
    protected void onPause() {
        try {
            unregisterReceiver(stateReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        super.onPause();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            }
        }
    }

    private void handleIncomingIntent(Intent intent) {
        if (intent == null) return;
        if (!Intent.ACTION_SEND.equals(intent.getAction())) return;
        if (!"text/plain".equals(intent.getType())) return;

        String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
        String sharedSubject = intent.getStringExtra(Intent.EXTRA_SUBJECT);
        if (sharedSubject != null && !sharedSubject.trim().isEmpty()) {
            titleInput.setText(sharedSubject.trim());
        }
        if (sharedText != null && !sharedText.trim().isEmpty()) {
            bodyInput.setText(sharedText);
            Toast.makeText(this, "Shared text loaded — tap Add Note", Toast.LENGTH_SHORT).show();
        }
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xFF0D1117);
        scroll.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(28));
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Title header
        TextView appTitle = new TextView(this);
        appTitle.setText("Watch Notes");
        appTitle.setTextColor(0xFFFFFFFF);
        appTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        appTitle.setTypeface(null, Typeface.BOLD);
        root.addView(appTitle);

        TextView appSub = new TextView(this);
        appSub.setText("Xiaomi Watch S5 Companion");
        appSub.setTextColor(0xFF8B949E);
        appSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        appSub.setPadding(0, dp(2), 0, dp(14));
        root.addView(appSub);

        // Server status card
        LinearLayout serverCard = makeCard(0xFF161B22, 0xFF30363D);
        serverCard.setOrientation(LinearLayout.HORIZONTAL);
        serverCard.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(serverCard, marginBottom(dp(14)));

        LinearLayout statusTextCol = new LinearLayout(this);
        statusTextCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        serverCard.addView(statusTextCol, colLp);

        statusTitleView = new TextView(this);
        statusTitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        statusTitleView.setTypeface(null, Typeface.BOLD);
        statusTextCol.addView(statusTitleView);

        statusSubView = new TextView(this);
        statusSubView.setTextColor(0xFF8B949E);
        statusSubView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        statusSubView.setPadding(0, dp(2), 0, 0);
        statusTextCol.addView(statusSubView);

        serverToggleBtn = makePillButton("Restart", 0xFF21262D, 0xFFE6EDF3);
        serverToggleBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (NotesServerService.isRunning()) {
                    Intent stop = new Intent(MainActivity.this, NotesServerService.class);
                    stop.setAction(NotesServerService.ACTION_STOP);
                    startService(stop);
                } else {
                    NotesServerService.ensureStarted(MainActivity.this);
                }
            }
        });
        serverCard.addView(serverToggleBtn);

        // Compose / Paste card
        LinearLayout composeCard = makeCard(0xFF161B22, 0xFF30363D);
        composeCard.setOrientation(LinearLayout.VERTICAL);
        root.addView(composeCard, marginBottom(dp(14)));

        TextView composeLabel = new TextView(this);
        composeLabel.setText("New Note");
        composeLabel.setTextColor(0xFF4FC3F7);
        composeLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        composeLabel.setTypeface(null, Typeface.BOLD);
        composeLabel.setPadding(0, 0, 0, dp(8));
        composeCard.addView(composeLabel);

        titleInput = makeInput("Title (optional — auto-fills from first line)", false);
        composeCard.addView(titleInput, marginBottom(dp(8)));

        bodyInput = makeInput("Paste or type note text here...", true);
        composeCard.addView(bodyInput, marginBottom(dp(10)));

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        composeCard.addView(btnRow);

        Button pasteBtn = makePillButton("Paste", 0xFF21262D, 0xFF4FC3F7);
        pasteBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pasteFromClipboard();
            }
        });
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        btnLp.setMarginEnd(dp(8));
        btnRow.addView(pasteBtn, btnLp);

        Button clearInputBtn = makePillButton("Clear", 0xFF21262D, 0xFF8B949E);
        clearInputBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                titleInput.setText("");
                bodyInput.setText("");
            }
        });
        LinearLayout.LayoutParams clearLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.8f);
        clearLp.setMarginEnd(dp(8));
        btnRow.addView(clearInputBtn, clearLp);

        Button addBtn = makePillButton("Add Note", 0xFF1F6FEB, 0xFFFFFFFF);
        addBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveNewNote();
            }
        });
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.3f);
        btnRow.addView(addBtn, addLp);

        // Undo delete bar (hidden by default)
        undoBar = makeCard(0xFF1C2333, 0xFF388BFD);
        undoBar.setOrientation(LinearLayout.HORIZONTAL);
        undoBar.setGravity(Gravity.CENTER_VERTICAL);
        undoBar.setVisibility(View.GONE);
        root.addView(undoBar, marginBottom(dp(12)));

        undoLabel = new TextView(this);
        undoLabel.setTextColor(0xFFE6EDF3);
        undoLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        undoBar.addView(undoLabel, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button undoBtn = makePillButton("Undo", 0xFF388BFD, 0xFFFFFFFF);
        undoBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (lastDeletedNote != null) {
                    NoteStore.restoreNote(MainActivity.this, lastDeletedNote, lastDeletedIndex);
                    lastDeletedNote = null;
                    undoBar.setVisibility(View.GONE);
                    refreshNotesList();
                }
            }
        });
        undoBar.addView(undoBtn);

        // Notes section header
        LinearLayout listHeaderRow = new LinearLayout(this);
        listHeaderRow.setOrientation(LinearLayout.HORIZONTAL);
        listHeaderRow.setGravity(Gravity.CENTER_VERTICAL);
        listHeaderRow.setPadding(dp(2), 0, dp(2), dp(8));
        root.addView(listHeaderRow);

        listHeaderView = new TextView(this);
        listHeaderView.setTextColor(0xFFE6EDF3);
        listHeaderView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        listHeaderView.setTypeface(null, Typeface.BOLD);
        listHeaderRow.addView(listHeaderView, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        clearAllBtn = makePillButton("Delete All", 0xFF2D1619, 0xFFFF7B72);
        clearAllBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmClearAll();
            }
        });
        listHeaderRow.addView(clearAllBtn);

        // Notes list container
        notesContainer = new LinearLayout(this);
        notesContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(notesContainer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(scroll);
    }

    private void pasteFromClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) {
            Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show();
            return;
        }
        CharSequence clipText = clip.getItemAt(0).coerceToText(this);
        if (clipText == null || clipText.length() == 0) {
            Toast.makeText(this, "No text in clipboard", Toast.LENGTH_SHORT).show();
            return;
        }
        String pasted = clipText.toString();
        String existing = bodyInput.getText().toString();
        if (existing.trim().isEmpty()) {
            bodyInput.setText(pasted);
        } else {
            bodyInput.setText(existing + "\n" + pasted);
        }
        bodyInput.setSelection(bodyInput.getText().length());
        Toast.makeText(this, "Pasted from clipboard", Toast.LENGTH_SHORT).show();
    }

    private void saveNewNote() {
        String title = titleInput.getText().toString();
        String body = bodyInput.getText().toString();
        if (title.trim().isEmpty() && body.trim().isEmpty()) {
            Toast.makeText(this, "Enter or paste some text first", Toast.LENGTH_SHORT).show();
            return;
        }
        NoteStore.addNote(this, title, body);
        titleInput.setText("");
        bodyInput.setText("");
        lastDeletedNote = null;
        undoBar.setVisibility(View.GONE);
        refreshNotesList();
        NotesServerService.ensureStarted(this);
        Toast.makeText(this, "Added — tap Sync Notes on your watch", Toast.LENGTH_SHORT).show();
    }

    private void refreshNotesList() {
        List<NoteStore.Note> notes = NoteStore.getNotes(this);
        listHeaderView.setText("Saved Notes (" + notes.size() + ")");
        clearAllBtn.setVisibility(notes.isEmpty() ? View.GONE : View.VISIBLE);
        notesContainer.removeAllViews();

        if (notes.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No notes yet. Paste or type text above to add one.");
            empty.setTextColor(0xFF8B949E);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            empty.setPadding(dp(8), dp(20), dp(8), dp(20));
            empty.setGravity(Gravity.CENTER);
            notesContainer.addView(empty);
            return;
        }

        for (final NoteStore.Note note : notes) {
            LinearLayout card = makeCard(0xFF161B22, 0xFF30363D);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showEditDialog(note);
                }
            });

            LinearLayout textCol = new LinearLayout(this);
            textCol.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            colLp.setMarginEnd(dp(10));
            card.addView(textCol, colLp);

            TextView titleView = new TextView(this);
            titleView.setText(note.title);
            titleView.setTextColor(0xFFFFFFFF);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            titleView.setTypeface(null, Typeface.BOLD);
            titleView.setSingleLine(true);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            textCol.addView(titleView);

            if (!note.body.isEmpty()) {
                TextView bodyPreview = new TextView(this);
                bodyPreview.setText(note.body);
                bodyPreview.setTextColor(0xFFB0BEC5);
                bodyPreview.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                bodyPreview.setMaxLines(3);
                bodyPreview.setEllipsize(TextUtils.TruncateAt.END);
                bodyPreview.setPadding(0, dp(4), 0, 0);
                textCol.addView(bodyPreview);
            }

            Button deleteBtn = makePillButton("Delete", 0xFF2D1619, 0xFFFF7B72);
            deleteBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    int idx = NoteStore.deleteNote(MainActivity.this, note.id);
                    if (idx >= 0) {
                        lastDeletedNote = note;
                        lastDeletedIndex = idx;
                        undoLabel.setText("Deleted \"" + note.title + "\"");
                        undoBar.setVisibility(View.VISIBLE);
                    }
                    refreshNotesList();
                }
            });
            card.addView(deleteBtn);

            notesContainer.addView(card, marginBottom(dp(10)));
        }
    }

    private void showEditDialog(final NoteStore.Note note) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(20), dp(14), dp(20), dp(4));

        final EditText editTitle = makeInput("Title", false);
        editTitle.setText(note.title);
        container.addView(editTitle, marginBottom(dp(10)));

        final EditText editBody = makeInput("Body", true);
        editBody.setText(note.body);
        container.addView(editBody);

        new AlertDialog.Builder(this)
                .setTitle("Edit Note")
                .setView(container)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        NoteStore.updateNote(
                                MainActivity.this,
                                note.id,
                                editTitle.getText().toString(),
                                editBody.getText().toString());
                        refreshNotesList();
                        Toast.makeText(MainActivity.this, "Updated — tap Sync Notes on watch",
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .setNeutralButton("Delete", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        int idx = NoteStore.deleteNote(MainActivity.this, note.id);
                        if (idx >= 0) {
                            lastDeletedNote = note;
                            lastDeletedIndex = idx;
                            undoLabel.setText("Deleted \"" + note.title + "\"");
                            undoBar.setVisibility(View.VISIBLE);
                        }
                        refreshNotesList();
                    }
                })
                .show();
    }

    private void confirmClearAll() {
        new AlertDialog.Builder(this)
                .setTitle("Delete All Notes?")
                .setMessage("This removes all saved notes from the phone server.")
                .setPositiveButton("Delete All", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        NoteStore.clearNotes(MainActivity.this);
                        lastDeletedNote = null;
                        undoBar.setVisibility(View.GONE);
                        refreshNotesList();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void updateServerBanner() {
        boolean running = NotesServerService.isRunning();
        long lastSync = NotesServerService.getLastSyncMillis();
        String err = NotesServerService.getLastError();

        if (running) {
            statusTitleView.setText("● Sync Server Active · :" + NotesServerService.PORT);
            statusTitleView.setTextColor(0xFF3FB950);
            serverToggleBtn.setText("Stop");
        } else {
            statusTitleView.setText("● Server Stopped" + (err.isEmpty() ? "" : " (" + err + ")"));
            statusTitleView.setTextColor(0xFFFF7B72);
            serverToggleBtn.setText("Start");
        }

        if (lastSync > 0L) {
            String timeStr = new SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    .format(new Date(lastSync));
            statusSubView.setText("Last watch sync: " + timeStr
                    + " (" + NotesServerService.getSyncCount() + " total)");
        } else {
            statusSubView.setText("Open Watch Notes on Watch S5 → tap Sync Notes");
        }
    }

    private LinearLayout makeCard(int bgColor, int strokeColor) {
        LinearLayout layout = new LinearLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), strokeColor);
        layout.setBackground(bg);
        layout.setPadding(dp(14), dp(12), dp(14), dp(12));
        return layout;
    }

    private EditText makeInput(String hint, boolean multiline) {
        EditText edit = new EditText(this);
        edit.setHint(hint);
        edit.setHintTextColor(0xFF6E7681);
        edit.setTextColor(0xFFE6EDF3);
        edit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF0D1117);
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), 0xFF30363D);
        edit.setBackground(bg);
        edit.setPadding(dp(12), dp(10), dp(12), dp(10));

        if (multiline) {
            edit.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            edit.setSingleLine(false);
            edit.setMinLines(4);
            edit.setMaxLines(7);
            edit.setGravity(Gravity.TOP | Gravity.START);
        } else {
            edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            edit.setSingleLine(true);
        }
        return edit;
    }

    private Button makePillButton(String text, int bgColor, int textColor) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setTextColor(textColor);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        btn.setTypeface(null, Typeface.BOLD);
        btn.setMinHeight(0);
        btn.setMinimumHeight(0);
        btn.setMinWidth(0);
        btn.setMinimumWidth(0);
        btn.setPadding(dp(14), dp(9), dp(14), dp(9));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(8));
        btn.setBackground(bg);
        return btn;
    }

    private LinearLayout.LayoutParams marginBottom(int px) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = px;
        return lp;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
