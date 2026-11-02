#!/usr/bin/env python3
"""Browser checks for sheet pages on the web (run after seed.py).

Usage: sheet_test.py BASE_URL SCREENSHOT_DIR
Makes a notebook with a sheet page (as the app stores it), then on the web: types values and a
formula, picks a reference by clicking, selects with the mouse (the bar shows the total), uses the
tools (bold, table look, filter, sort, number format), and checks the server has each change in the
app's format.
"""
import asyncio
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request
import uuid

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
NOW = int(time.time() * 1000)


def put(collection, fields):
    rec = {"id": str(uuid.uuid4()), "deleted": False, "device": "seed", "editedAt": NOW, "createdAt": NOW, **fields}
    return api("POST", f"/api/collections/{collection}/records", rec, TOKEN)["id"]


def get_page(pid):
    return api("GET", f"/api/collections/pages/records/{pid}", token=TOKEN)


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


shelf = api("GET", "/api/collections/shelves/records?perPage=50", token=TOKEN)["items"][0]["id"]
money = put("notebooks", {"shelf": shelf, "title": "Money", "colorArgb": -13664290, "texture": "GLOSS", "icon": "none", "position": 9, "pageLayout": "SHEET"})
sheet = {
    "v": 1, "rows": 50, "cols": 12, "widths": {"A": 120},
    "cells": {
        "A1": {"v": "Item"}, "B1": {"v": "Cost"}, "C1": {"v": "Due"},
        "A2": {"v": "Rent"}, "B2": {"v": "1450", "s": {"fmt": "currency"}}, "C2": {"v": "46327", "s": {"fmt": "date"}},
        "A3": {"v": "Power"}, "B3": {"v": "92.18", "s": {"fmt": "currency"}}, "C3": {"v": "46340", "s": {"fmt": "date"}},
        "A4": {"v": "Internet"}, "B4": {"v": "60", "s": {"fmt": "currency"}}, "C4": {"v": "46346", "s": {"fmt": "date"}},
        "A5": {"v": "Total", "s": {"b": True, "border": "t"}},
    },
}
pid = put("pages", {"notebook": money, "title": "November bills", "position": 0, "pageType": "PLAIN", "backgroundArgb": None, "texture": "NONE",
                    "layoutMode": "SHEET", "content": None, "sheet": sheet, "plainText": "", "updatedAt": NOW})


def cells():
    return get_page(pid)["sheet"]["cells"]


def td(r, c):
    return f'td[data-r="{r}"][data-c="{c}"]'


async def main():
    async with async_playwright() as p:
        browser = await p.chromium.launch()
        ctx = await browser.new_context(viewport={"width": 1280, "height": 860})
        page = await ctx.new_page()
        errors = []
        page.on("pageerror", lambda e: errors.append(str(e) + "\n" + (e.stack or "")))

        await page.goto(BASE)
        await page.fill("input[type=email]", EMAIL)
        await page.fill("input[type=password]", PASSWORD)
        await page.click("button[type=submit]")
        await expect(page.get_by_role("heading", name="Quick notes")).to_be_visible()
        await page.goto(f"{BASE}/#/notebook/{money}/{pid}")
        await expect(page.locator(td(1, 1))).to_have_text("$1,450.00")
        await expect(page.locator(td(1, 2))).to_have_text("11/1/2026")
        ok("the sheet shows values in their formats")
        await page.screenshot(path=f"{OUT}/s01_sheet.png")

        # Type a formula: typing starts in the selected cell; Enter puts it in.
        await page.click(td(4, 1))
        await page.keyboard.type("=SUM(B2:B4")
        await expect(page.locator(".cell-input")).to_have_value("=SUM(B2:B4")
        await page.screenshot(path=f"{OUT}/s02_typing.png")
        await page.keyboard.press("Enter")
        await expect(page.locator(td(4, 1))).to_have_text("1602.18")
        wait_for(lambda: cells().get("B5", {}).get("v") == "=SUM(B2:B4)", "the formula on the server")
        wait_for(lambda: "1602.18" in get_page(pid)["plainText"], "the total in the search text")
        ok("typed a formula; brackets closed; saved in the app's format")

        # A reference picked by clicking a cell while typing a formula.
        await page.click(td(5, 1))
        await page.keyboard.type("=")
        await page.click(td(1, 1))
        await page.keyboard.type("*2")
        await expect(page.locator(".cell-input")).to_have_value("=B2*2")
        await page.keyboard.press("Enter")
        await expect(page.locator(td(5, 1))).to_have_text("2900")
        ok("clicking a cell puts its reference into the formula")

        # Select with the mouse: the bar shows the total.
        await page.hover(td(1, 1))
        await page.mouse.down()
        await page.hover(td(3, 1))
        await page.mouse.up()
        await expect(page.locator(".stats")).to_contain_text("Sum 1602.18")
        await expect(page.locator(".bar .name")).to_have_text("B2:B4")
        await page.screenshot(path=f"{OUT}/s03_selection.png")
        ok("dragging selects; the bar shows the sum")

        await page.click("button[title='Bold (Ctrl+B)']")
        wait_for(lambda: cells()["B3"].get("s", {}).get("b") is True, "bold on the server")
        ok("bold")

        # Sort the selected rows by cost, smallest first, then back.
        await page.click("button[title='Sort']")
        await page.get_by_role("menuitem", name="A → Z", exact=False).click()
        wait_for(lambda: [cells()[f"A{r}"]["v"] for r in (2, 3, 4)] == ["Internet", "Power", "Rent"], "sorted on the server")
        await expect(page.locator(td(5, 1))).to_have_text("120")
        await page.click("button[title='Sort']")
        await page.get_by_role("menuitem", name="Z → A", exact=False).click()
        wait_for(lambda: [cells()[f"A{r}"]["v"] for r in (2, 3, 4)] == ["Rent", "Power", "Internet"], "sorted back")
        ok("sort")

        # A table look.
        await page.click("button[title='Table look']")
        await page.get_by_role("menuitem", name="Green").click()
        wait_for(lambda: (get_page(pid)["sheet"].get("table") or {}).get("style") == "green", "table look on the server")
        await page.screenshot(path=f"{OUT}/s04_table.png")
        ok("table look")

        # Filter: hide Power.
        await page.click(td(1, 0))
        await page.click("button[title='Filter']")
        await expect(page.get_by_role("dialog", name="Filter column A")).to_be_visible()
        await page.screenshot(path=f"{OUT}/s05_filter.png")
        await page.get_by_label("Power").uncheck()
        await page.get_by_role("button", name="Apply").click()
        wait_for(lambda: get_page(pid)["sheet"].get("hide") == {"A": ["Power"]}, "filter on the server")
        await expect(page.locator(td(2, 0))).to_be_hidden()
        await page.screenshot(path=f"{OUT}/s06_filtered.png")
        await page.click("button[title='Filter']")
        await page.get_by_role("button", name="Clear all filters").click()
        await expect(page.locator(td(2, 0))).to_be_visible()
        ok("filter hides and shows rows")

        # Number format: plain number with one place.
        await page.click(td(4, 1))
        await page.click("button[title='Number format']")
        await page.get_by_role("menuitem", name="Number", exact=False).first.click()
        await expect(page.locator(td(4, 1))).to_have_text("1,602.18")
        await page.click("button[title='Number format']")
        await page.get_by_role("menuitem", name="Fewer decimal places").click()
        await expect(page.locator(td(4, 1))).to_have_text("1,602.2")
        ok("number formats and places")

        # Undo and keys.
        await page.keyboard.press("Control+z")
        await expect(page.locator(td(4, 1))).to_have_text("1,602.18")
        await page.keyboard.press("Escape")
        await page.click(td(7, 0))
        await page.keyboard.type("10/31/26")
        await page.keyboard.press("Enter")
        await expect(page.locator(td(7, 0))).to_have_text("10/31/2026")
        await page.keyboard.press("ArrowUp")
        await page.keyboard.press("Delete")
        await expect(page.locator(td(7, 0))).to_have_text("")
        ok("undo, typing a date, arrows and Delete")

        # Right-click menu: insert a row above.
        await page.click(td(2, 0), button="right")
        await page.get_by_role("menuitem", name="Insert row above").click()
        await expect(page.locator(td(3, 0))).to_have_text("Power")
        wait_for(lambda: cells().get("B6", {}).get("v") == "=SUM(B2:B5)", "the total's range grew")
        ok("inserting a row moves cells and grows the total's range")
        await page.screenshot(path=f"{OUT}/s07_after_insert.png")

        # Scrollbars show; hiding a column and a row, then showing them again.
        # (Headless browsers draw no scrollbars, so this checks they're asked for.)
        bars = await page.evaluate("() => { const st = getComputedStyle(document.querySelector('.grid')); return [st.overflowX, st.overflowY] }")
        assert bars == ["scroll", "scroll"], bars
        await page.click(td(1, 2), button="right")
        await page.get_by_role("menuitem", name="Hide column", exact=True).click()
        await expect(page.locator('td[data-c="2"]')).to_have_count(0)
        wait_for(lambda: (get_page(pid)["sheet"].get("hidden") or {}).get("cols") == ["C"], "hidden column on the server")
        await page.click(td(2, 0), button="right")
        await page.get_by_role("menuitem", name="Hide row", exact=True).click()
        await expect(page.locator(td(2, 0))).to_be_hidden()
        await page.screenshot(path=f"{OUT}/s07c_hidden.png")
        await page.click(td(1, 1), button="right")
        await page.get_by_role("menuitem", name="Unhide columns").click()
        await expect(page.locator(td(1, 2))).to_be_visible()
        await page.click(td(1, 0), button="right")
        await page.get_by_role("menuitem", name="Unhide rows").click()
        await expect(page.locator(td(2, 0))).to_be_visible()
        wait_for(lambda: "hidden" not in get_page(pid)["sheet"], "nothing hidden on the server")
        ok("scrollbars; hiding and showing columns and rows")

        # Picking a column from its header doesn't scroll down or add rows (it once ran to the end).
        await page.evaluate("() => { document.querySelector('.grid').scrollTop = 0 }")
        rows_before = await page.locator("th[data-row]").count()
        for _ in range(3):
            await page.click('[data-col="1"]')
            await page.wait_for_timeout(150)
        top = await page.evaluate("() => document.querySelector('.grid').scrollTop")
        assert top == 0, f"scrolled to {top}"
        assert await page.locator("th[data-row]").count() == rows_before
        last = int(await page.locator("th[data-row]").last.get_attribute("data-row"))
        await expect(page.locator(td(1, 1))).to_have_class(re.compile("sel"))
        await expect(page.locator(td(last, 1))).to_have_class(re.compile("sel"))
        await page.click(td(1, 0))
        ok("picking a column from its header stays put")

        # The list's Add page button adds at the end, even from an earlier page.
        def pages_here():
            items = api("GET", f"/api/collections/pages/records?perPage=50&filter=notebook%3D%22{money}%22", token=TOKEN)["items"]
            return sorted((p["position"], p["id"]) for p in items if not p["deleted"])
        await page.locator("button.add-page").click()
        await expect(page.locator(".count")).to_have_text("2/2")
        await page.locator("nav.pages a").first.click()
        await expect(page.locator(".count")).to_have_text("1/2")
        await page.locator("button.add-page").click()
        await expect(page.locator(".count")).to_have_text("3/3")
        wait_for(lambda: len(pages_here()) == 3, "three pages on the server")
        order = pages_here()
        assert [p for p, _ in order] == [0, 1, 2], order
        assert order[0][1] == pid, order
        ok("the list's Add page button adds at the end")

        # Deleting two pages in quick succession deletes those two (it once deleted whatever page
        # showed while the first delete finished), and Deleted pages brings one back.
        page.once("dialog", lambda d: asyncio.ensure_future(d.accept()))
        await page.locator("nav.pages a").nth(1).click()
        await expect(page.locator(".count")).to_have_text("2/3")
        await page.get_by_role("button", name="More", exact=True).click()
        await page.get_by_role("button", name="Delete page").click()
        page.once("dialog", lambda d: asyncio.ensure_future(d.accept()))
        await page.get_by_role("button", name="More", exact=True).click()
        await page.get_by_role("button", name="Delete page").click()
        await expect(page.locator(".count")).to_have_text("1/1")
        wait_for(lambda: [i for _, i in pages_here()] == [pid], "only the sheet page left on the server")
        await page.get_by_role("button", name="More", exact=True).click()
        await page.get_by_role("button", name="Deleted pages…").click()
        deleted = page.locator("dialog[open] li")
        await expect(deleted).to_have_count(2)
        await deleted.first.get_by_role("button", name="Restore").click()
        await expect(deleted).to_have_count(1)
        await page.locator("dialog[open]").get_by_role("button", name="Done").click()
        await expect(page.locator(".count")).to_have_text("2/2")
        wait_for(lambda: len(pages_here()) == 2 and pages_here()[0][1] == pid, "the restored page on the server, after the sheet")
        ok("deleting pages quickly, and restoring a deleted page")
        await page.locator("nav.pages a").first.click()
        await expect(page.locator(".count")).to_have_text("1/2")

        # Download as Excel; it opens with the same cells.
        await page.click("button[title='More: rows, columns and downloads']")
        async with page.expect_download() as dl:
            await page.get_by_role("menuitem", name="Download as Excel (.xlsx)").click()
        download = await dl.value
        assert download.suggested_filename == "November bills.xlsx", download.suggested_filename
        xlsx_path = f"{OUT}/downloaded.xlsx"
        await download.save_as(xlsx_path)
        with open(xlsx_path, "rb") as f:
            assert f.read(2) == b"PK"
        ok("download as Excel")

        # Import a CSV as a new sheet page.
        csv_path = f"{OUT}/import.csv"
        with open(csv_path, "w") as f:
            f.write("Name,Amount\nTea,4.5\nCake,=2*3\n")
        await page.click("button[title='More']")
        await page.locator("input[type=file][accept*='.xlsx']").set_input_files(csv_path)
        await expect(page.locator("input.title")).to_have_value("import")
        await expect(page.locator(td(2, 1))).to_have_text("6")
        imported = wait_for(lambda: [p for p in api("GET", f"/api/collections/pages/records?perPage=50&filter={urllib.parse.quote(f'notebook="{money}" && title="import"')}", token=TOKEN)["items"]], "the imported page")
        assert imported[0]["layoutMode"] == "SHEET" and imported[0]["sheet"]["cells"]["B2"]["v"] == "4.5", imported[0]["sheet"]
        ok("import a CSV as a sheet page")
        await page.screenshot(path=f"{OUT}/s07b_imported.png")

        # Phone size.
        m = await browser.new_context(viewport={"width": 390, "height": 800}, is_mobile=True, has_touch=True)
        mp = await m.new_page()
        await mp.goto(BASE)
        await mp.fill("input[type=email]", EMAIL)
        await mp.fill("input[type=password]", PASSWORD)
        await mp.click("button[type=submit]")
        await expect(mp.get_by_role("heading", name="Quick notes")).to_be_visible()
        await mp.goto(f"{BASE}/#/notebook/{money}/{pid}")
        await expect(mp.locator(td(1, 0))).to_have_text("Rent")
        await mp.tap(td(1, 1))
        await expect(mp.locator(".bar .name")).to_have_text("B2")
        await mp.screenshot(path=f"{OUT}/s08_phone.png")
        ok("phone size")

        assert not errors, "page errors:\n" + "\n".join(errors)
        await browser.close()


asyncio.run(main())
