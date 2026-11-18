!!!!Note: The code in this repo was generated with AI assistance (Gemini) and tested on real hardware (Xiaomi Watch S5 + Xiaomi 15 on HyperOS).!!!!!


# Watch Notes — Offline Notes Sync for Xiaomi Watch S5

A Xiaomi Vela JS Quick App for the **Xiaomi Watch S5** (`480x480` round screen, `designWidth: 240`) paired with a lightweight **Android companion app** (no Android Studio / Gradle needed) and an optional Python 3 server.

## How It Works

On the Xiaomi Watch S5, `@system.fetch` requests to `http://127.0.0.1:8765/notes.json` are proxied through **Mi Fitness** on the paired Android phone.

- **Phone Companion App (`android-app/`)**: Runs an HTTP server (`0.0.0.0:8765`) inside an Android foreground service. Paste text from the clipboard, share text via Android's **Share → Watch Notes** menu, edit, or delete notes — then tap **↻ Sync Notes** on the watch.
- **Watch App (`src/`)**: Caches synced notes in `@system.storage` (`saved_notes`) so notes stay readable offline.
- **Round-Screen & Vela UI Details**:
  - Safe circular padding (`24px 20px` on the list page, `26px 22px` on the detail page) for the Watch S5 display.
  - Vela `<list-item>` does not wrap multi-line `<text>`, so `detail.ux` splits paragraphs and word-wraps lines at 26 characters via `wrapText()`, rendering each line as an individual `22px` `<list-item>`.
  - Uses `protected:` properties on `detail.ux` so `router.push` params (`title`, `body`) populate properly.

## Project Structure

```text
.
├── src/                        # Vela JS Quick App (Xiaomi Watch S5)
│   ├── manifest.json           # com.custom.watchnotes, designWidth: 240
│   └── pages/
│       ├── index/index.ux      # Sync button, offline cache, notes list
│       └── detail/detail.ux    # Back header + 26-char paragraph wrapper
├── android-app/                # Native Android companion app (CLI SDK build)
│   ├── AndroidManifest.xml
│   ├── build.sh                # Builds & signs WatchNotesCompanion.apk
│   └── src/com/custom/watchnotes/companion/
│       ├── MainActivity.java
│       ├── NotesServerService.java
│       └── NoteStore.java
└── phone-server/               # Optional Python 3 stdlib server & CLI helper
    ├── notes_server.py
    └── add_note.py
```

## Build & Install

### 1. Watch App (`.rpk`)
```bash
npm install
npm run build
# Output: dist/com.custom.watchnotes.debug.<version>.rpk
```
Sideload onto the Watch S5 via Mi Fitness's hidden `ThirdAppDebugFragment` (see [oryonatan/xiaomi-band-development](https://github.com/oryonatan/xiaomi-band-development) for the sideloading tooling). On Xiaomi/HyperOS phones, enter `com.custom.watchnotes` in the `input package name` dialog before tapping `install third app`.

### 2. Android Companion App (`.apk`)
Requires `javac` and Android SDK `platforms` + `build-tools` (`aapt2`, `d8`, `zipalign`, `apksigner`):
```bash
cd android-app
./build.sh
# Output: build/WatchNotesCompanion.apk
```
Install on your phone via `adb install build/WatchNotesCompanion.apk` (with **Install via USB** enabled in Developer options) or copy the APK to your phone and open it in the Files app.

### 3. Optional Python Server (PC or Termux)
```bash
cd phone-server
python3 notes_server.py
python3 add_note.py "Shopping List" "Oat milk\nCoffee beans"
```

## Credits

Sideloading approach and Vela Quick App structure based on [oryonatan/xiaomi-band-development](https://github.com/oryonatan/xiaomi-band-development).
