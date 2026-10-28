// @vitest-environment jsdom
import { readFileSync, readdirSync } from "node:fs"
import { join } from "node:path"
import { Editor } from "@tiptap/core"
import { TextSelection } from "@tiptap/pm/state"
import { afterEach, describe, expect, it } from "vitest"
import { editorExtensions, insertDivider, toggleBlockType, indent } from "../src/lib/editor/schema"
import { fromEditor, toEditor, type RichDoc, type PMNode } from "../src/lib/richdoc"

const dir = join(__dirname, "../../shared/fixtures/richdoc")

const made: Editor[] = []
afterEach(() => made.splice(0).forEach((e) => e.destroy()))

function editorFor(doc: RichDoc) {
  const e = new Editor({ extensions: editorExtensions(), content: toEditor(doc) as never })
  made.push(e)
  return e
}
const docOf = (e: Editor) => fromEditor(e.getJSON() as PMNode)

/** Puts the cursor at [offset] in block [index]. */
function cursor(e: Editor, index: number, offset: number) {
  let pos = 0
  e.state.doc.forEach((node, p, i) => {
    if (i === index) pos = p + 1 + offset
  })
  e.view.dispatch(e.state.tr.setSelection(TextSelection.create(e.state.doc, pos)))
}
const press = (e: Editor, key: string) =>
  e.view.someProp("handleKeyDown", (f) => f(e.view, new KeyboardEvent("keydown", { key })))

describe("editor", () => {
  for (const f of readdirSync(dir).filter((x) => x.endsWith(".json"))) {
    it(`loads and saves ${f} unchanged`, () => {
      const doc = JSON.parse(readFileSync(join(dir, f), "utf8")) as RichDoc
      expect(docOf(editorFor(doc))).toEqual(doc)
    })
  }

  it("Enter continues a list and an empty item ends it", () => {
    const e = editorFor({ blocks: [{ id: "a", type: "BULLET", text: "Eggs" }] })
    cursor(e, 0, 4)
    press(e, "Enter")
    let d = docOf(e)
    expect(d.blocks.map((b) => b.type)).toEqual(["BULLET", "BULLET"])
    expect(new Set(d.blocks.map((b) => b.id)).size).toBe(2)
    press(e, "Enter")
    d = docOf(e)
    expect(d.blocks.map((b) => b.type ?? "BODY")).toEqual(["BULLET", "BODY"])
  })

  it("Enter after a heading gives body text", () => {
    const e = editorFor({ blocks: [{ id: "h", type: "HEADING", text: "Title" }] })
    cursor(e, 0, 5)
    press(e, "Enter")
    expect(docOf(e).blocks.map((b) => b.type ?? "BODY")).toEqual(["HEADING", "BODY"])
  })

  it("Backspace at the start of a task makes it body text first", () => {
    const e = editorFor({ blocks: [{ id: "t", type: "TASK", text: "Do it", checked: true }] })
    cursor(e, 0, 0)
    press(e, "Backspace")
    expect(docOf(e).blocks[0]).toEqual({ id: "t", text: "Do it" })
  })

  it("toolbar commands", () => {
    const e = editorFor({ blocks: [{ id: "a", text: "one" }, { id: "b", text: "two" }] })
    e.view.dispatch(e.state.tr.setSelection(TextSelection.create(e.state.doc, 1, e.state.doc.content.size - 1)))
    toggleBlockType(e, "TASK")
    indent(e, 1)
    expect(docOf(e).blocks).toEqual([
      { id: "a", type: "TASK", text: "one", indent: 1 },
      { id: "b", type: "TASK", text: "two", indent: 1 },
    ])
    cursor(e, 1, 3)
    insertDivider(e)
    expect(docOf(e).blocks.map((b) => b.type ?? "BODY")).toEqual(["TASK", "TASK", "DIVIDER", "BODY"])
  })
})
