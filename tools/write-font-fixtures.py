"""Rewrite the two font fixtures with the exact codepoints the merge test needs.

plugin-a claims U+F001 (a space provider) and U+F010 (a bitmap glyph); plugin-b claims U+F010 again, which
is the collision the test is about, plus U+F020 of its own. The characters are built with chr() rather than
typed, so the bytes on disk do not depend on an editor, a terminal or a clipboard keeping private-use
characters intact — which is exactly the reason the tool escapes them on the way out as well.

Run from anywhere: python tools/write-font-fixtures.py
"""
import json
import pathlib

root = pathlib.Path(__file__).resolve().parents[1] / "src/test/resources"

packs = {
    "plugin-a": {
        "providers": [
            {"type": "space", "advances": {chr(0xF001): 4}},
            {"type": "bitmap", "file": "a:font/icons.png", "ascent": 8, "chars": [chr(0xF010)]},
        ]
    },
    "plugin-b": {
        "providers": [
            {"type": "bitmap", "file": "b:font/icons.png", "ascent": 8, "chars": [chr(0xF010)]},
            {"type": "bitmap", "file": "b:font/badges.png", "ascent": 7, "chars": [chr(0xF020)]},
            {"type": "reference", "id": "b:extras"},
        ]
    },
}

for pack, document in packs.items():
    target = root / pack / "assets/minecraft/font/default.json"
    # Newlines are pinned so a rerun on Windows does not rewrite every line of the fixture.
    target.write_text(json.dumps(document, indent=2, ensure_ascii=False) + "\n", encoding="utf-8", newline="\n")
    codepoints = [hex(ord(c)) for c in target.read_text(encoding="utf-8") if ord(c) > 127]
    print("wrote", target.relative_to(root), codepoints)
