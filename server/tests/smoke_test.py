#!/usr/bin/env python3
"""Checks the sync server's rules against a running PocketBase.

Usage: smoke_test.py BASE_URL SUPERUSER_EMAIL SUPERUSER_PASSWORD

Creates two test users (or reuses them), then checks: records are created with the device's id
and owned by the signed-in user, updates need the right base revision, other users can't see or
change them, nothing can be deleted through the API, and nobody can sign up.
"""
import json
import urllib.parse
import sys
import urllib.error
import urllib.request
import uuid

BASE, SU_EMAIL, SU_PASS = sys.argv[1].rstrip("/"), sys.argv[2], sys.argv[3]


def call(method, path, body=None, token=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", token)
    try:
        with urllib.request.urlopen(req) as r:
            raw = r.read()
            return r.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as e:
        raw = e.read()
        return e.code, json.loads(raw) if raw else None


def check(cond, message):
    if not cond:
        print("FAIL:", message)
        sys.exit(1)
    print("ok:", message)


status, su = call("POST", "/api/collections/_superusers/auth-with-password", {"identity": SU_EMAIL, "password": SU_PASS})
check(status == 200, "superuser can sign in")
su_token = su["token"]


def user(email):
    call("POST", "/api/collections/users/records", {"email": email, "password": "testpass123", "passwordConfirm": "testpass123"}, su_token)
    status, auth = call("POST", "/api/collections/users/auth-with-password", {"identity": email, "password": "testpass123"})
    check(status == 200, f"{email} can sign in")
    return auth["token"], auth["record"]["id"]


tim, tim_id = user("tim@test.local")
other, _ = user("other@test.local")

# Sign-up is closed.
status, _ = call("POST", "/api/collections/users/records", {"email": "x@test.local", "password": "testpass123", "passwordConfirm": "testpass123"})
check(status in (400, 403), "nobody can sign up")

uid = str(uuid.uuid4())
note = {"id": uid, "title": "Hello", "content": {"blocks": []}, "plainText": "", "colorKey": "default", "rev": 99, "owner": "somebodyelse", "editedAt": 1}
status, rec = call("POST", "/api/collections/quick_notes/records", note, tim)
check(status == 200, f"create a record with the device's id ({status} {rec})")
check(rec["id"] == uid, "record id is the device's uid")
check(rec["rev"] == 1, "a new record starts at rev 1")
check(rec["owner"] == tim_id, "the record belongs to the signed-in user")

hex_uid = uuid.uuid4().hex
status, rec2 = call("POST", "/api/collections/shelves/records", {"id": hex_uid, "name": "Shelf"}, tim)
check(status == 200 and rec2["id"] == hex_uid, "32-digit ids made by the database migration work too")

status, _ = call("POST", "/api/collections/quick_notes/records", note, tim)
check(status == 400, "creating the same id twice is refused")

status, body = call("PATCH", f"/api/collections/quick_notes/records/{uid}", {"title": "No base"}, tim)
check(status == 409, "an update without a base revision is refused")

status, rec = call("PATCH", f"/api/collections/quick_notes/records/{uid}", {"title": "Edited", "baseRev": 1, "owner": "x"}, tim)
check(status == 200 and rec["rev"] == 2 and rec["title"] == "Edited", "an update on the current revision goes through and bumps rev")
check(rec["owner"] == tim_id, "the owner can't be changed")

status, body = call("PATCH", f"/api/collections/quick_notes/records/{uid}", {"title": "Stale", "baseRev": 1}, tim)
check(status == 409, "an update on an old revision is refused")
status, rec = call("GET", f"/api/collections/quick_notes/records/{uid}", token=tim)
check(status == 200 and rec["rev"] == 2 and rec["title"] == "Edited", "the current version can be fetched after a refusal")

status, _ = call("GET", f"/api/collections/quick_notes/records/{uid}", token=other)
check(status == 404, "another user can't see it")
status, _ = call("PATCH", f"/api/collections/quick_notes/records/{uid}", {"title": "Hijack", "baseRev": 2}, other)
check(status == 404, "another user can't change it")
status, page = call("GET", "/api/collections/quick_notes/records?perPage=50", token=other)
check(status == 200 and page["totalItems"] == 0, "another user's list is empty")

status, _ = call("DELETE", f"/api/collections/quick_notes/records/{uid}", token=tim)
check(status in (403, 404), "records can't be deleted through the API")

status, rec = call("PATCH", f"/api/collections/quick_notes/records/{uid}", {"deleted": True, "baseRev": 2}, tim)
check(status == 200 and rec["deleted"] is True, "deleting marks the record")

big = "x" * 100000
status, rec = call("PATCH", f"/api/collections/quick_notes/records/{uid}", {"plainText": big, "baseRev": 3}, tim)
check(status == 200 and len(rec["plainText"]) == 100000, "long notes fit")

status, page = call("GET", "/api/collections/quick_notes/records?filter=" + urllib.parse.quote(f"updated >= '2000-01-01 00:00:00'") + "&sort=updated,id&perPage=500", token=tim)
check(status == 200 and page["totalItems"] == 1, "changes can be listed by update time")

for name, rec in [
    ("pages", {"id": str(uuid.uuid4()), "notebook": hex_uid, "backgroundArgb": None, "content": {"blocks": [{"text": "a"}]}, "plainText": "a"}),
    ("page_elements", {"id": str(uuid.uuid4()), "page": hex_uid, "kind": "SHAPE", "x": 0.25, "data": {"shape": "RECT"}}),
    ("notebooks", {"id": str(uuid.uuid4()), "shelf": hex_uid, "title": "NB", "colorArgb": -16777216}),
    ("attachments", {"id": str(uuid.uuid4()), "ownerType": "NOTE", "ownerUid": uid, "kind": "IMAGE", "path": "media/a.jpg", "width": None}),
]:
    status, body = call("POST", f"/api/collections/{name}/records", rec, tim)
    check(status == 200, f"create in {name} ({status} {body})")

print("All server checks passed.")
