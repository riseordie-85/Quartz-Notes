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

        # Typing here while another device changes the text: the edit here is newer, so it stays,
        # and the phone's version is kept as a conflict copy.
        await editor.locator(".blk-text", has_text="Coffee beans").click()
        await page.keyboard.press("End")
        await page.keyboard.press("Enter")
        await page.keyboard.type("Oat milk")
        current = find("quick_notes", 'title="Groceries (from phone)"')[0]
        api("PATCH", f"/api/collections/quick_notes/records/{current['id']}", {
            "content": {"blocks": [{"text": "Written on the phone"}]}, "plainText": "Written on the phone",
            "baseRev": current["rev"], "device": "phone", "editedAt": int(time.time() * 1000) - 60000,
        }, TOKEN)
        wait_for(lambda: "Oat milk" in api("GET", f"/api/collections/quick_notes/records/{current['id']}", token=TOKEN)["plainText"], "the edit here to win")
        copy = wait_for(lambda: next(iter(find("quick_notes", 'title="Groceries (from phone) (conflict copy)"')), None), "a conflict copy")
        assert copy["plainText"] == "Written on the phone", copy
        assert not copy["pinned"], copy
        await expect(page.locator("input.title")).to_have_value("Groceries (from phone)")
        ok("when both change a note, the newer edit wins and the other is kept as a copy")

        # The other way round: the phone's edit is newer, so it shows here and the typing becomes the copy.
        await editor.locator(".blk-text", has_text="Oat milk").click()
        await page.keyboard.press("End")
        await page.keyboard.type(" (typed in the browser)")
        current = api("GET", f"/api/collections/quick_notes/records/{current['id']}", token=TOKEN)
        api("PATCH", f"/api/collections/quick_notes/records/{current['id']}", {
            "content": {"blocks": [{"text": "Newer, from the phone"}]}, "plainText": "Newer, from the phone",
            "baseRev": current["rev"], "device": "phone", "editedAt": int(time.time() * 1000) + 60000,
        }, TOKEN)
        mine = wait_for(lambda: next((n for n in find("quick_notes", 'title="Groceries (from phone) (conflict copy)"') if "typed in the browser" in n["plainText"]), None), "the typing kept as a copy")
        assert "Oat milk" in mine["plainText"], mine
        await expect(editor).to_have_text("Newer, from the phone", timeout=8000)
        assert api("GET", f"/api/collections/quick_notes/records/{current['id']}", token=TOKEN)["plainText"] == "Newer, from the phone"
        ok("when the other edit is newer, it shows here and the typing is kept as a copy")

        # Pictures: a synced one on a note, and one added from the browser.
        await page.goto(f"{BASE}/#/")
        await page.get_by_role("link", name="Gift ideas").click()
        await page.wait_for_function("() => [...document.querySelectorAll('.attachments img')].some(i => i.complete && i.naturalWidth === 120)")
        ok("a note's synced picture shows")
        pic = os.path.join(OUT, "upload-test.png")
        with open(pic, "wb") as f:
            import zlib, struct
            raw = b"".join(b"\x00" + bytes([x % 256, y % 256, 128]) * 1 + b"".join(bytes([x % 256, y % 256, 128]) for x in range(1, 300)) for y in range(200) for x in [0])
            def chunk(k, d):
                return struct.pack(">I", len(d)) + k + d + struct.pack(">I", zlib.crc32(k + d) & 0xFFFFFFFF)
            f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 300, 200, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))
        await page.locator("input[type=file]").set_input_files(pic)
        await expect(page.locator(".attachments .thumb")).to_have_count(2, timeout=10000)
        gift = find("quick_notes", 'title="Gift ideas"')[0]
        atts = find("attachments", f'ownerUid="{gift["id"]}"')
        added = [a for a in atts if a["path"] != "media/sunset.png"]
        assert len(added) == 1 and added[0]["width"] == 300 and added[0]["height"] == 200 and added[0]["path"].startswith("media/"), added
        media = find("media", f'path="{added[0]["path"]}"')
        assert len(media) == 1, media
        ok("a picture added in the browser is uploaded and attached to the note")

        # The picture viewer: opens on the picture clicked, arrows and keys move through the note's pictures.
        await page.locator(".attachments .open-picture").first.click()
        viewer = page.get_by_role("dialog", name="Picture viewer")
        await expect(viewer).to_be_visible()
        await expect(viewer.locator(".count")).to_have_text("1 / 2")
        first_src = await viewer.locator(".stage img").get_attribute("src")
        await viewer.get_by_role("button", name="Next (→)").click()
        await expect(viewer.locator(".count")).to_have_text("2 / 2")
        assert await viewer.locator(".stage img").get_attribute("src") != first_src
        await page.keyboard.press("ArrowRight")
        await expect(viewer.locator(".count")).to_have_text("1 / 2")
        link = viewer.get_by_role("link", name="Open in new tab")
        assert await link.get_attribute("target") == "_blank"
        assert await link.get_attribute("href") == first_src
        await page.wait_for_function("() => { const i = document.querySelector('.lightbox .stage img'); return i && i.complete && i.naturalWidth > 0 }")
        await page.screenshot(path=f"{OUT}/w03b_viewer.png")
        await page.keyboard.press("Escape")
        await expect(viewer).to_have_count(0)
        ok("pictures open in a viewer with arrows, keys and an open-in-new-tab button")

        # A new note.
        await page.goto(f"{BASE}/#/")
        await page.get_by_role("button", name="New note", exact=True).click()
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
        photo = page.locator(".layer .photo img")
        await expect(photo).to_have_count(1)
        await page.wait_for_function("() => { const i = document.querySelector('.layer .photo img'); return i && i.complete && i.naturalWidth === 160 }")
        ok("a synced picture shows in its frame")
        await page.locator(".layer .photo .open-picture").click()
        await expect(page.get_by_role("dialog", name="Picture viewer").locator(".caption")).to_have_text("Kyoto")
        await expect(page.get_by_role("button", name="Next (→)")).to_have_count(0)
        await page.get_by_role("button", name="Close (Esc)").click()
        ok("a page's picture opens in the viewer with its caption")
        await page.locator(".layer .voice-item button.play").click()
        await page.wait_for_timeout(600)
        await expect(page.locator(".layer .voice-item button.play")).to_have_attribute("aria-label", "Pause")
        ok("a voice note plays")
        await page.screenshot(path=f"{OUT}/w06_scrapbook.png", full_page=True)
        await page.keyboard.press("Alt+ArrowRight")
        await page.screenshot(path=f"{OUT}/w07_grid_dark_page.png")

        # Add a picture to the (dark) page.
        await page.locator("input[type=file]").set_input_files(pic)
        await expect(page.locator(".layer .photo img")).to_have_count(1, timeout=10000)
        budget = find("pages", 'title="Budget"')[0]
        imgs = find("page_elements", f'page="{budget["id"]}" && kind="IMAGE"')
        assert len(imgs) == 1 and imgs[0]["data"]["frame"] == "WHITE" and abs(imgs[0]["data"]["aspect"] - 200 / 300) < 0.01, imgs
        ok("a picture added to a page becomes a framed picture item")
        await page.screenshot(path=f"{OUT}/w07b_page_with_picture.png")

        # Add a page after the current one.
        await page.get_by_role("button", name="Add a page after this one").click()
        await expect(page.locator(".count")).to_have_text("5/5")
        wait_for(lambda: len([p for p in api("GET", "/api/collections/pages/records?perPage=50", token=TOKEN)["items"] if not p["deleted"]]) >= 5, "the new page on the server")
        ok("a new page is added")

        # The page arrows stay in the same place on every page (pictures can't go on the section page,
        # which used to hide a button and shift them).
        spots = set()
        for _ in range(4):
            await page.keyboard.press("Alt+ArrowLeft")
            await page.wait_for_timeout(150)
            box = await page.get_by_role("button", name="Next page (Alt+→)").bounding_box()
            spots.add((round(box["x"]), round(box["y"])))
        await expect(page.locator(".count")).to_have_text("1/5")
        assert len(spots) == 1, spots
        ok("the page arrows don't move between pages")
        await page.screenshot(path=f"{OUT}/w07c_section_topbar.png")

        # Page menu: lock, then delete the new page.
        async def accept(d):
            await d.accept()
        page.on("dialog", accept)
        for _ in range(4):
            await page.keyboard.press("Alt+ArrowRight")
            await page.wait_for_timeout(150)
        await expect(page.locator(".count")).to_have_text("5/5")
        await page.get_by_role("button", name="More").click()
        await page.screenshot(path=f"{OUT}/w07d_notebook_menu.png")
        await page.get_by_role("button", name="Lock page").click()
        wait_for(lambda: [p for p in api("GET", "/api/collections/pages/records?perPage=50", token=TOKEN)["items"] if p["position"] == 4 and not p["deleted"] and p["locked"]], "the page to lock")
        ok("Lock page in the menu works")
        await page.get_by_role("button", name="More").click()
        await page.get_by_role("button", name="Delete page").click()
        await expect(page.locator(".count")).to_have_text("4/4")
        wait_for(lambda: len([p for p in api("GET", "/api/collections/pages/records?perPage=50", token=TOKEN)["items"] if not p["deleted"]]) == 4, "the page deleted on the server")
        ok("Delete page works")

        # Page menu: move the current page (Budget, 4th) earlier, then back.
        budget_id = find("pages", 'title="Budget"')[0]["id"]
        await page.get_by_role("button", name="More").click()
        await page.get_by_role("button", name="Move earlier").click()
        wait_for(lambda: find("pages", f'id="{budget_id}"')[0]["position"] == 2, "the page to move earlier")
        await expect(page.locator(".count")).to_have_text("3/4")
        await page.get_by_role("button", name="More").click()
        await page.get_by_role("button", name="Move later").click()
        wait_for(lambda: find("pages", f'id="{budget_id}"')[0]["position"] == 3, "the page to move back")
        ok("pages move earlier and later from the menu")

        # Drag a page to the top of the page list.
        def page_titles():
            nb = find("notebooks", 'title="Travel"')[0]
            return [p["title"] for p in sorted(find("pages", f'notebook="{nb["id"]}" && deleted=false'), key=lambda p: p["position"])]

        await page.locator("nav.pages a", has_text="Budget").drag_to(page.locator("nav.pages a", has_text="Kyoto"), target_position={"x": 40, "y": 4})
        wait_for(lambda: page_titles() == ["Budget", "Kyoto", "Japan", "Mood board"], "the dragged page order on the server")
        await expect(page.locator("nav.pages a").first).to_contain_text("Budget")
        ok("dragging a page in the list moves it")
        await page.locator("nav.pages a", has_text="Budget").drag_to(page.locator("nav.pages .drop-end"))
        wait_for(lambda: page_titles() == ["Kyoto", "Japan", "Mood board", "Budget"], "the page dragged back to the end")

        # Notebook settings: title, colour and icon.
        await page.get_by_role("button", name="More").click()
        await page.get_by_role("button", name="Notebook settings").click()
        await page.locator("dialog[open]").get_by_role("textbox", name="Title").fill("Travel 2026")
        await page.locator("dialog[open]").get_by_role("radio", name="astronaut").click()
        await page.locator("dialog[open] .swatch").nth(4).click()
        await page.screenshot(path=f"{OUT}/w07e_notebook_settings.png")
        await page.get_by_role("button", name="Save").click()
        await expect(page.get_by_role("heading", name="Travel 2026")).to_be_visible()
        nb = wait_for(lambda: next(iter(find("notebooks", 'title="Travel 2026"')), None), "the new settings on the server")
        assert nb["icon"] == "astronaut" and nb["colorArgb"] == 0xFF2E9E5B - 2**32, nb
        ok("a notebook's title, colour and icon can be changed")

        def order(shelf_name):
            s = find("shelves", f'name="{shelf_name}"')[0]
            return [n["title"] for n in sorted(find("notebooks", f'shelf="{s["id"]}" && deleted=false'), key=lambda n: n["position"])]

        await page.get_by_role("button", name="More").click()
        await page.get_by_role("button", name="Move down").click()
        wait_for(lambda: order("Notebooks") == ["Recipes", "Travel 2026"], "the new order on the server")
        sidebar = page.locator("aside nav a")
        await expect(sidebar.nth(2)).to_contain_text("Recipes")
        await expect(sidebar.nth(3)).to_contain_text("Travel 2026")
        ok("a notebook can be moved down")

        # Drag a notebook onto another shelf in the sidebar.
        await page.locator("aside nav a", has_text="Recipes").drag_to(page.locator("aside nav a", has_text="Meetings"), target_position={"x": 40, "y": 4})
        wait_for(lambda: order("Work") == ["Recipes", "Meetings"] and order("Notebooks") == ["Travel 2026"], "the drag to reach the server")
        ok("dragging a notebook in the sidebar moves it, also onto another shelf")
        await page.screenshot(path=f"{OUT}/w07f_rearranged.png")

        # Delete a notebook.
        await page.locator("aside nav a", has_text="Recipes").click()
        await page.get_by_role("button", name="More").click()
        await page.get_by_role("button", name="Delete notebook").click()
        await expect(page.get_by_role("heading", name="Quick notes")).to_be_visible()
        wait_for(lambda: not find("notebooks", 'title="Recipes" && deleted=false'), "the notebook deleted on the server")
        await expect(page.locator("aside nav a", has_text="Recipes")).to_have_count(0)
        ok("a notebook can be deleted")

        # A new notebook, on the Work shelf, with one of the new icons.
        await page.get_by_role("button", name="New notebook").click()
        await page.locator("dialog[open]").get_by_role("textbox", name="Title").fill("Comics")
        await page.locator("dialog[open]").get_by_role("combobox", name="Shelf").select_option(label="Work")
        await page.locator("dialog[open]").get_by_role("radio", name="skull", exact=True).click()
        await page.screenshot(path=f"{OUT}/w07g_new_notebook.png")
        await page.get_by_role("button", name="Create").click()
        await expect(page.get_by_role("heading", name="Comics")).to_be_visible()
        await expect(page.locator(".count")).to_have_text("1/1")
        comics = wait_for(lambda: next(iter(find("notebooks", 'title="Comics"')), None), "the new notebook on the server")
        assert comics["icon"] == "skull" and comics["shelf"] == find("shelves", 'name="Work"')[0]["id"], comics
        assert len(find("pages", f'notebook="{comics["id"]}"')) == 1
        ok("a new notebook can be made, with its first page")

        # Section pages show the new (drawn) icons too.
        japan = find("pages", 'title="Japan"')[0]
        api("PATCH", f"/api/collections/pages/records/{japan['id']}", {"sectionIcon": "pirate", "baseRev": japan["rev"], "device": "phone", "editedAt": int(time.time() * 1000)}, TOKEN)
        await page.locator("aside nav a", has_text="Travel 2026").click()
        await page.locator("nav.pages a", has_text="Japan").click()
        await expect(page.locator(".section-icon svg")).to_be_visible()
        ok("a section shows one of the new icons")

        # Light, dark and the app's colours.
        await page.get_by_role("radio", name="Dark").click()
        await expect(page.locator("html")).to_have_attribute("data-theme", "dark")
        await page.screenshot(path=f"{OUT}/w07h_dark.png")
        await page.get_by_role("radio", name="Light").click()
        await expect(page.locator("html")).to_have_attribute("data-theme", "light")
        await page.reload()
        await expect(page.locator("html")).to_have_attribute("data-theme", "light")
        ok("light and dark can be chosen, and the choice is remembered")
        api("POST", "/api/collections/prefs/records", {"accentArgb": 0xFF0050D0 - 2**32, "backgroundArgb": 0xFFE9F0F6 - 2**32, "editedAt": int(time.time() * 1000), "device": "phone"}, TOKEN)
        await page.wait_for_function("() => getComputedStyle(document.documentElement).getPropertyValue('--accent').trim() === 'rgb(0 80 208)'", timeout=8000)
        bg = await page.evaluate("() => getComputedStyle(document.body).backgroundColor")
        assert bg == "rgb(233, 240, 246)", bg
        await page.screenshot(path=f"{OUT}/w07i_app_colours.png")
        ok("the accent and background picked in the app show on the web")
        await page.get_by_role("radio", name="Match this computer").click()

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
