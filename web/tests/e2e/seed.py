#!/usr/bin/env python3
"""Fills a test sync server with a sample account (demo@test.local / testpass123) and notes that use
every feature: rich text, sections, scrapbook items. Used by the web app's browser tests.

Usage: seed.py BASE_URL SUPERUSER_EMAIL SUPERUSER_PASSWORD
"""
import json
import sys
import time
import urllib.request
import uuid

BASE, SU_EMAIL, SU_PASS = sys.argv[1].rstrip("/"), sys.argv[2], sys.argv[3]
EMAIL, PASSWORD = "demo@test.local", "testpass123"


def call(method, path, body=None, token=None):
    req = urllib.request.Request(BASE + path, data=json.dumps(body).encode() if body is not None else None, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", token)
    try:
        with urllib.request.urlopen(req) as r:
            return json.loads(r.read() or b"null")
    except urllib.error.HTTPError as e:
        raise SystemExit(f"{method} {path} failed: {e.code} {e.read().decode()}")


def signed(argb):
    return argb - (1 << 32) if argb >= (1 << 31) else argb


su = call("POST", "/api/collections/_superusers/auth-with-password", {"identity": SU_EMAIL, "password": SU_PASS})["token"]
try:
    call("POST", "/api/collections/users/records", {"email": EMAIL, "password": PASSWORD, "passwordConfirm": PASSWORD}, su)
except SystemExit:
    pass
token = call("POST", "/api/collections/users/auth-with-password", {"identity": EMAIL, "password": PASSWORD})["token"]
now = int(time.time() * 1000)


def put(collection, fields):
    rec = {"id": str(uuid.uuid4()), "deleted": False, "device": "seed", "editedAt": now, "createdAt": now, **fields}
    return call("POST", f"/api/collections/{collection}/records", rec, token)["id"]


def doc(*blocks):
    return {"blocks": [{"id": str(uuid.uuid4()), **b} for b in blocks]}


def plain(d):
    return "\n".join(b.get("text", "") for b in d["blocks"])


shelf = put("shelves", {"name": "Notebooks", "position": 0})
work = put("shelves", {"name": "Work", "position": 1})
travel = put("notebooks", {"shelf": shelf, "title": "Travel", "colorArgb": signed(0xFF1E6FE8), "texture": "LEATHER", "icon": "travel", "position": 0, "twoPage": True, "sectionTabs": True})
put("notebooks", {"shelf": shelf, "title": "Recipes", "colorArgb": signed(0xFFD6004C), "texture": "GLOSS", "icon": "food", "position": 1})
put("notebooks", {"shelf": work, "title": "Meetings", "colorArgb": signed(0xFF2E9E5B), "texture": "GLOSS", "icon": "work", "position": 0})

day1 = doc(
    {"type": "HEADING", "text": "Day one in Kyoto"},
    {"text": "Arrived early and walked to the shrine before the crowds.", "spans": [{"start": 32, "end": 38, "style": "BOLD"}, {"start": 50, "end": 56, "style": "HIGHLIGHT", "value": "FFFFE14D"}]},
    {"type": "SUBHEADING", "text": "To do"},
    {"type": "TASK", "text": "Buy a rail pass", "checked": True},
    {"type": "TASK", "text": "Book the tea ceremony"},
    {"type": "BULLET", "text": "Fushimi Inari"},
    {"type": "BULLET", "text": "Early morning is best", "indent": 1},
    {"type": "DIVIDER"},
    {"text": "Dinner was great.", "align": "CENTER", "spans": [{"start": 0, "end": 17, "style": "COLOR", "value": "FFD6004C"}, {"start": 0, "end": 17, "style": "FONT", "value": "serif"}]},
)
put("pages", {"notebook": travel, "title": "Kyoto", "position": 0, "pageType": "LINES", "backgroundArgb": None, "texture": "NONE", "layoutMode": "INLINE", "content": day1, "plainText": plain(day1), "updatedAt": now})
put("pages", {"notebook": travel, "title": "Japan", "position": 1, "pageType": "PLAIN", "backgroundArgb": signed(0xFF6CCFF6), "texture": "NONE", "layoutMode": "INLINE", "content": doc({"text": "Two weeks, five cities."}), "plainText": "Two weeks, five cities.", "isSection": True, "sectionIcon": "travel", "updatedAt": now})
board = put("pages", {"notebook": travel, "title": "Mood board", "position": 2, "pageType": "DOTTED", "backgroundArgb": signed(0xFFFFF8E7), "texture": "PAPER", "layoutMode": "SCRAPBOOK", "content": None, "plainText": "", "updatedAt": now})
grid = doc({"type": "SUBHEADING", "text": "Budget"}, {"text": "Hotels 40%, food 30%, trains 30%"})
put("pages", {"notebook": travel, "title": "Budget", "position": 3, "pageType": "GRID", "backgroundArgb": signed(0xFF162033), "texture": "NONE", "layoutMode": "INLINE", "content": grid, "plainText": plain(grid), "updatedAt": now})

text_doc = json.dumps(doc({"type": "HEADING", "text": "Ideas"}, {"type": "BULLET", "text": "Temples at dawn"}, {"type": "BULLET", "text": "Night markets"}))
put("page_elements", {"page": board, "kind": "TEXT", "x": 0.06, "y": 0.22, "width": 0.5, "height": 0, "rotation": 0, "z": 1, "data": {"doc": text_doc}})
a = put("page_elements", {"page": board, "kind": "SHAPE", "x": 0.08, "y": 0.75, "width": 0.3, "height": 0.3, "z": 2, "data": {"shape": "ELLIPSE", "fill": signed(0xFF00A8D6), "outline": signed(0xFF111114), "outlineLevel": 3, "text": "Start", "textColor": 0}})
b = put("page_elements", {"page": board, "kind": "SHAPE", "x": 0.55, "y": 1.05, "width": 0.36, "height": 0.24, "z": 3, "data": {"shape": "ROUNDED", "fill": signed(0xFFFFD600), "outline": 0, "outlineLevel": 2, "text": "Finish", "textColor": 0}})
put("page_elements", {"page": board, "kind": "SHAPE", "x": 0.7, "y": 0.72, "width": 0.2, "height": 0.2, "z": 4, "data": {"shape": "STAR", "fill": signed(0xFFD6004C), "outline": signed(0xFF111114), "outlineLevel": 0, "text": "", "textColor": 0}})
put("page_elements", {"page": board, "kind": "CONNECTOR", "z": 5, "data": {"from": a, "to": b, "route": "ELBOW", "color": 0, "level": 3, "endHead": True}})
put("page_elements", {"page": board, "kind": "ARROW", "x": 0.62, "y": 0.3, "z": 6, "data": {"dx": 0.2, "dy": 0.15, "color": signed(0xFFD6004C), "level": 3, "startHead": False, "endHead": True}})
put("page_elements", {"page": board, "kind": "STROKE", "z": 7, "data": {"points": [0.1, 1.45, 0.2, 1.4, 0.3, 1.48, 0.4, 1.42, 0.5, 1.47], "color": signed(0xFF7C4DFF), "level": 3}})
put("page_elements", {"page": board, "kind": "IMAGE", "x": 0.58, "y": 0.42, "width": 0.36, "height": 0.27, "rotation": 3, "z": 8, "data": {"path": "media/missing.jpg", "aspect": 0.75, "frame": "POLAROID", "tape": True, "shadow": True, "caption": "Kyoto", "placement": "FLOAT"}})
put("page_elements", {"page": board, "kind": "VOICE", "x": 0.06, "y": 1.6, "width": 0.7, "z": 9, "data": {"path": "media/v.m4a", "durationMs": 83000, "attached": False}})

notes = [
    ("Groceries", doc({"type": "TASK", "text": "Milk", "checked": True}, {"type": "TASK", "text": "Eggs"}, {"type": "TASK", "text": "Bread"}), "yellow", True, False),
    ("Gift ideas", doc({"type": "BULLET", "text": "Headphones"}, {"type": "BULLET", "text": "A good book"}), "purple", False, False),
    ("", doc({"text": "Call the dentist on Monday about the appointment."}), "default", False, False),
    ("Old plans", doc({"text": "Archived thoughts."}), "gray", False, True),
]
for i, (title, d, color, pinned, archived) in enumerate(notes):
    put("quick_notes", {"title": title, "content": d, "plainText": plain(d), "colorKey": color, "pinned": pinned, "archived": archived, "createdAt": now - i * 60000, "updatedAt": now})

print("Seeded demo account", EMAIL)
