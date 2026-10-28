import { readFileSync, readdirSync } from "node:fs"
import { join } from "node:path"
import { describe, expect, it } from "vitest"
import { compactDoc, cssColor, argbToCss, decodeDoc, fromEditor, normalizeSpans, plainText, toEditor, type RichDoc } from "../src/lib/richdoc"

const dir = join(__dirname, "../../shared/fixtures/richdoc")
const fixtures = readdirSync(dir).filter((f) => f.endsWith(".json"))

describe("sample notes survive the editor", () => {
  it("has samples", () => expect(fixtures.length).toBeGreaterThan(0))
  for (const f of fixtures) {
    it(f, () => {
      const doc = JSON.parse(readFileSync(join(dir, f), "utf8")) as RichDoc
      const back = fromEditor(toEditor(decodeDoc(doc)))
      expect(back).toEqual(compactDoc(doc))
      // Already in the app's own shape, so nothing changes at all.
      expect(back).toEqual(doc)
    })
  }
})

describe("format details", () => {
  it("reads note bodies stored as text or plain text", () => {
    expect(decodeDoc('{"blocks":[{"id":"x","text":"hi"}]}').blocks[0].text).toBe("hi")
    expect(decodeDoc("line one\nline two").blocks.map((b) => b.text)).toEqual(["line one", "line two"])
    expect(decodeDoc(null).blocks.length).toBe(1)
  })

  it("merges spans like the app", () => {
    expect(
      normalizeSpans([
        { start: 5, end: 9, style: "BOLD" },
        { start: 0, end: 5, style: "BOLD" },
        { start: 2, end: 2, style: "ITALIC" },
        { start: 1, end: 3, style: "HIGHLIGHT", value: "A" },
        { start: 3, end: 4, style: "HIGHLIGHT", value: "B" },
      ]),
    ).toEqual([
      { start: 0, end: 9, style: "BOLD" },
      { start: 1, end: 3, style: "HIGHLIGHT", value: "A" },
      { start: 3, end: 4, style: "HIGHLIGHT", value: "B" },
    ])
  })

  it("makes search text the same way", () => {
    expect(plainText({ blocks: [{ id: "a", text: "one" }, { id: "b" }, { id: "c", text: "two" }] })).toBe("one\n\ntwo")
  })

  it("converts colors", () => {
    expect(cssColor("FFFFE14D")).toBe("#FFE14DFF")
    expect(cssColor("#D6004C")).toBe("#D6004C")
    expect(argbToCss(0xff2e86de | 0)).toBe("rgb(46 134 222)")
  })
})
