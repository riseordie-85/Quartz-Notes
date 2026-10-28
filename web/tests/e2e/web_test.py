#!/usr/bin/env python3
"""Browser checks for the web app against a seeded test server (see seed.py).

Usage: web_test.py BASE_URL SCREENSHOT_DIR
Signs in, edits a quick note and a notebook page (checking the server got the changes in the app's
format), checks a change made elsewhere shows up live, creates a note and a page, and takes
screenshots at desktop and phone sizes.
"""
import asyncio
import json
import os
import sys
import time
import urllib.request

from playwright.async_api import async_playwright, expect

BASE, OUT = sys.argv[1].rstrip("/"), sys.argv[2]
os.makedirs(OUT, exist_ok=True)
EMAIL, PASSWORD = "demo@test.local", "testpass123"


def api(method, path, body=None, token=None):
    req = urllib.request.Request(BASE + path, data=json.dumps(body).encode() if body is not None else None, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", token)
    with urllib.request.urlopen(req) as r:
        return json.loads(r.read() or b"null")


TOKEN = api("POST", "/api/collections/users/auth-with-password", {"identity": EMAIL, "password": PASSWORD})["token"]


def find(collection, filt):
    q = urllib.parse.quote(filt)
    return api("GET", f"/api/collections/{collection}/records?filter={q}&perPage=50", token=TOKEN)["items"]


def wait_for(fn, what, timeout=8):
    end = time.time() + timeout
    while time.time() < end:
        v = fn()
        if v:
            return v
        time.sleep(0.25)
    raise AssertionError(f"timed out waiting for: {what}")


def ok(msg):
    print("ok:", msg)


async def main():
    async with async_playwright() as p:
        browser = await p.chromium.launch()
        ctx = await browser.new_context(viewport={"width": 1280, "height": 860})
        page = await ctx.new_page()
        errors = []
        page.on("pageerror", lambda e: errors.append(str(e) + "\n" + (e.stack or "")))

        await page.goto(BASE)
        await page.screenshot(path=f"{OUT}/w01_login.png")
        await page.fill("input[type=email]", EMAIL)
        await page.fill("input[type=password]", PASSWORD)
        await page.click("button[type=submit]")
        await expect(page.get_by_role("heading", name="Quick notes")).to_be_visible()
        await expect(page.locator("article.note")).to_have_count(3)
        ok("signed in and sees the quick notes (archived one hidden)")
        await page.screenshot(path=f"{OUT}/w02_notes.png")

        # Edit a quick note: type a new checklist item and make a word bold.
        await page.get_by_role("link", name="Groceries").click()
        editor = page.locator(".ProseMirror")
        await expect(editor).to_be_visible()
        await editor.locator(".blk-text", has_text="Bread").click()
        await page.keyboard.press("End")
        await page.keyboard.press("Enter")
        await page.keyboard.type("Coffee beans")
        await page.keyboard.press("Shift+Home")
        await page.keyboard.press("Control+b")
        note = wait_for(
            lambda: next((n for n in find("quick_notes", 'title="Groceries"') if "Coffee beans" in n["plainText"]), None),
            "the edit to reach the server",
        )
        blocks = note["content"]["blocks"]
        coffee = next(b for b in blocks if b.get("text") == "Coffee beans")
        assert coffee["type"] == "TASK", coffee
        ok("typing Enter after a task makes another task, saved to the server")
        note = wait_for(
            lambda: next((n for n in find("quick_notes", 'title="Groceries"') if any(s["style"] == "BOLD" for b in n["content"]["blocks"] for s in b.get("spans", []))), None),
            "bold to reach the server",
        )
        coffee = next(b for b in note["content"]["blocks"] if b.get("text") == "Coffee beans")
        assert coffee.get("spans") == [{"start": 0, "end": 12, "style": "BOLD"}], coffee
        assert note["plainText"].endswith("Coffee beans"), note["plainText"]
        assert note["device"].startswith("web-"), note["device"]
        ok("bold is saved as the app's span format")
        await page.screenshot(path=f"{OUT}/w03_note_edited.png")

        # Another device changes the title: it shows up here without reloading.
        api("PATCH", f"/api/collections/quick_notes/records/{note['id']}", {"title": "Groceries (from phone)", "baseRev": note["rev"], "device": "phone", "editedAt": int(time.time() * 1000)}, TOKEN)
        await expect(page.locator("input.title")).to_have_value("Groceries (from phone)", timeout=8000)
        ok("a change from another device appears live")

        # A new note.
        await page.goto(f"{BASE}/#/")
        await page.get_by_role("button", name="New note").click()
        await expect(page.locator(".ProseMirror")).to_be_visible()
        await page.fill("input.title", "Made on the web")
        await page.locator(".ProseMirror").click()
        await page.keyboard.type("Hello from the browser")
        wait_for(lambda: find("quick_notes", 'title="Made on the web" && plainText="Hello from the browser"'), "the new note on the server")
        ok("a new note is created and saved")

        # Notebook page editing.
        await page.get_by_role("link", name="Travel").click()
        await expect(page.locator("input.title")).to_have_value("Kyoto")
        await page.locator(".ProseMirror .blk-text", has_text="Dinner was great.").click()
        await page.keyboard.press("End")
        await page.keyboard.type(" Ramen!")
        wait_for(lambda: [p for p in find("pages", 'title="Kyoto"') if "Ramen!" in p["plainText"]], "the page edit on the server")
        ok("page text edits are saved")
        await page.screenshot(path=f"{OUT}/w04_page.png", full_page=True)

        await page.keyboard.press("Alt+ArrowRight")
        await expect(page.locator("input.section-title")).to_have_value("Japan")
        await page.screenshot(path=f"{OUT}/w05_section.png")
        await page.keyboard.press("Alt+ArrowRight")
        await expect(page.locator(".layer .text-box")).to_have_count(1)
        await expect(page.locator(".layer .lines path")).not_to_have_count(0)
        ok("scrapbook items are drawn")
        await page.screenshot(path=f"{OUT}/w06_scrapbook.png", full_page=True)
        await page.keyboard.press("Alt+ArrowRight")
        await page.screenshot(path=f"{OUT}/w07_grid_dark_page.png")

        # Add a page after the current one.
        await page.get_by_role("button", name="Add a page after this one").click()
        await expect(page.locator(".count")).to_have_text("5/5")
        wait_for(lambda: len([p for p in api("GET", "/api/collections/pages/records?perPage=50", token=TOKEN)["items"] if not p["deleted"]]) >= 5, "the new page on the server")
        ok("a new page is added")

        # Search.
        await page.fill("input[type=search]", "ramen")
        await page.keyboard.press("Enter")
        await expect(page.locator(".hit")).to_have_count(1)
        ok("search finds page text")

        # Phone size.
        phone = await browser.new_context(viewport={"width": 390, "height": 844}, device_scale_factor=2, storage_state=await ctx.storage_state())
        m = await phone.new_page()
        await m.goto(f"{BASE}/#/")
        await expect(m.get_by_role("heading", name="Quick notes")).to_be_visible()
        await m.screenshot(path=f"{OUT}/w08_phone_notes.png")
        await m.get_by_role("button", name="Menu").first.click()
        await m.wait_for_timeout(300)
        await m.screenshot(path=f"{OUT}/w09_phone_menu.png")
        await m.get_by_role("link", name="Travel").click()
        await m.wait_for_timeout(600)
        await m.screenshot(path=f"{OUT}/w10_phone_page.png")

        assert not errors, errors
        ok("no page errors")
        await browser.close()
        print("All web checks passed.")


asyncio.run(main())
