#!/usr/bin/env python3
"""CLI helper to prepend a new note to notes.json (Termux / Share menu friendly)."""

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import sys

DEFAULT_NOTES_FILE = Path(__file__).resolve().parent / "notes.json"


def load_notes(notes_path: Path) -> list:
    """Load existing notes list from notes.json, or return empty list if absent."""
    if not notes_path.exists():
        return []
    raw = notes_path.read_text(encoding="utf-8").strip()
    if not raw:
        return []
    data = json.loads(raw)
    if not isinstance(data, list):
        raise ValueError(f"Expected JSON array in {notes_path}, got {type(data).__name__}")
    return data


def prepend_note(notes_path: Path, title: str, body: str) -> dict:
    """Prepend a note dict to notes.json and return the created note."""
    notes = load_notes(notes_path)
    next_id = max((n.get("id", 0) for n in notes if isinstance(n, dict)), default=0) + 1
    note = {
        "id": next_id,
        "title": title.strip() or "Untitled",
        "body": body,
        "created_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    }
    notes.insert(0, note)
    notes_path.parent.mkdir(parents=True, exist_ok=True)
    tmp_path = notes_path.with_suffix(".json.tmp")
    tmp_path.write_text(json.dumps(notes, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    tmp_path.replace(notes_path)
    return note


def main() -> int:
    parser = argparse.ArgumentParser(description='Prepend a note: add_note.py "<title>" "<body>"')
    parser.add_argument("title", help="Note title")
    parser.add_argument("body", nargs="?", default="", help='Note body (use "-" to read from stdin)')
    parser.add_argument("--file", type=Path, default=DEFAULT_NOTES_FILE, help="Path to notes.json")
    args = parser.parse_args()

    body = sys.stdin.read() if args.body == "-" else args.body
    note = prepend_note(args.file.resolve(), args.title, body)
    print(f'Added note #{note["id"]}: {note["title"]}')
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
