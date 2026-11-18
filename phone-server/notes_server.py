#!/usr/bin/env python3
"""Local HTTP server serving notes.json for the Xiaomi Watch S5 Watch Notes app."""

import argparse
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path

DEFAULT_HOST = "0.0.0.0"
DEFAULT_PORT = 8765
DEFAULT_NOTES_FILE = Path(__file__).resolve().parent / "notes.json"

SAMPLE_NOTES = [
    {
        "id": 1,
        "title": "Welcome to Watch Notes",
        "body": (
            "Tap Sync Notes on your Watch S5 to fetch updates from this local "
            "phone server.\n\n"
            "Cached notes are saved on-device so you can read them offline anytime."
        ),
        "created_at": "2026-11-18T12:00:00Z",
    },
    {
        "id": 2,
        "title": "Grocery List",
        "body": "Oat milk\nCoffee beans\nSourdough bread\nBananas\nDark chocolate",
        "created_at": "2026-11-18T12:05:00Z",
    },
    {
        "id": 3,
        "title": "Adding Notes via Termux",
        "body": (
            'Run: python3 add_note.py "Title" "Your note body here"\n\n'
            "New notes are prepended to notes.json and appear at the top after "
            "the next sync."
        ),
        "created_at": "2026-11-18T12:10:00Z",
    },
]


def ensure_notes_file(notes_path: Path) -> None:
    """Create notes.json with sample notes if it does not exist yet."""
    if not notes_path.exists():
        notes_path.parent.mkdir(parents=True, exist_ok=True)
        notes_path.write_text(
            json.dumps(SAMPLE_NOTES, indent=2, ensure_ascii=False) + "\n",
            encoding="utf-8",
        )
        print(f"Created sample notes at {notes_path}")


def make_handler(notes_path: Path):
    """Build a request handler bound to the target notes.json path."""

    class NotesHandler(BaseHTTPRequestHandler):
        def do_GET(self) -> None:
            request_path = self.path.split("?", 1)[0]
            if request_path not in ("/notes.json", "/"):
                self.send_response(404)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.end_headers()
                self.wfile.write(b'{"error":"Not found"}\n')
                return

            ensure_notes_file(notes_path)
            try:
                payload = notes_path.read_bytes()
                # Validate JSON before serving so corrupt edits fail loudly.
                json.loads(payload.decode("utf-8"))
            except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
                self.send_response(500)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.end_headers()
                err = json.dumps({"error": str(exc)}).encode("utf-8")
                self.wfile.write(err)
                return

            self.send_response(200)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(payload)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(payload)

        def log_message(self, fmt: str, *args) -> None:
            now = datetime.now(timezone.utc).strftime("%H:%M:%S")
            print(f"[{now}] {self.address_string()} - {fmt % args}")

    return NotesHandler


def main() -> None:
    parser = argparse.ArgumentParser(description="Serve notes.json for Watch Notes")
    parser.add_argument("--host", default=DEFAULT_HOST, help=f"Bind host (default: {DEFAULT_HOST})")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help=f"Bind port (default: {DEFAULT_PORT})")
    parser.add_argument("--file", type=Path, default=DEFAULT_NOTES_FILE, help="Path to notes.json")
    args = parser.parse_args()

    notes_path = args.file.resolve()
    ensure_notes_file(notes_path)

    server = ThreadingHTTPServer((args.host, args.port), make_handler(notes_path))
    print(f"Serving {notes_path} at http://{args.host}:{args.port}/notes.json")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nShutting down server.")
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
