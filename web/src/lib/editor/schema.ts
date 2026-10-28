// The web editor's document model, mirroring the app's: a flat list of paragraphs ("para") with a
// block type, alignment, indent and checkbox, plus in-line pictures and dividers. Character styles
// are marks. See richdoc.ts for the conversion to and from the app's format.

import { Extension, Mark, Node, mergeAttributes, type Editor } from "@tiptap/core"
import Bold from "@tiptap/extension-bold"
import Italic from "@tiptap/extension-italic"
import Underline from "@tiptap/extension-underline"
import Strike from "@tiptap/extension-strike"
import { Placeholder, UndoRedo } from "@tiptap/extensions"
import { Plugin, PluginKey, TextSelection, type EditorState, type Transaction } from "@tiptap/pm/state"
import type { Node as PMNode } from "@tiptap/pm/model"
import { cssColor, cssFont, newId, type Align, type BlockType } from "../richdoc"

export const MAX_INDENT = 6
const LIST_TYPES: string[] = ["BULLET", "TASK"]

// ---- Nodes ----

const Doc = Node.create({ name: "doc", topNode: true, content: "block+" })
const Text = Node.create({ name: "text", group: "inline" })

/** A paragraph: body text, heading, subheading, bullet or task. */
export const Para = Node.create({
  name: "para",
  group: "block",
  content: "inline*",
  priority: 1000,

  addAttributes() {
    return {
      blockId: { default: null, keepOnSplit: false, parseHTML: (el) => el.getAttribute("data-id"), renderHTML: () => ({}) },
      blockType: {
        default: "BODY",
        parseHTML: (el) => el.getAttribute("data-type") ?? typeFromTag(el),
        renderHTML: (a) => ({ "data-type": a.blockType }),
      },
      align: {
        default: "START",
        parseHTML: (el) => el.getAttribute("data-align") ?? "START",
        renderHTML: (a) => ({ "data-align": a.align }),
      },
      checked: {
        default: false,
        keepOnSplit: false,
        parseHTML: (el) => el.getAttribute("data-checked") === "true",
        renderHTML: (a) => (a.checked ? { "data-checked": "true" } : {}),
      },
      indent: {
        default: 0,
        parseHTML: (el) => Number(el.getAttribute("data-indent") ?? 0) || 0,
        renderHTML: (a) => ({ style: `--indent: ${a.indent}` }),
      },
    }
  },

  parseHTML() {
    return [{ tag: "p" }, { tag: "h1" }, { tag: "h2" }, { tag: "h3" }, { tag: "h4" }, { tag: "li" }, { tag: "div.blk" }]
  },

  renderHTML({ HTMLAttributes }) {
    return ["p", mergeAttributes(HTMLAttributes, { class: "blk" }), 0]
  },

  addNodeView() {
    return ({ node, getPos, editor }) => {
      const dom = document.createElement("div")
      dom.className = "blk"
      const box = document.createElement("button")
      box.type = "button"
      box.className = "task-box"
      box.contentEditable = "false"
      box.tabIndex = -1
      box.setAttribute("aria-label", "Done")
      const content = document.createElement("div")
      content.className = "blk-text"
      dom.append(box, content)
      let current = node
      const paint = (n: PMNode) => {
        current = n
        dom.dataset.type = n.attrs.blockType
        dom.dataset.align = n.attrs.align
        dom.dataset.checked = n.attrs.checked ? "true" : "false"
        dom.style.setProperty("--indent", String(n.attrs.indent ?? 0))
        box.hidden = n.attrs.blockType !== "TASK"
        box.setAttribute("aria-pressed", n.attrs.checked ? "true" : "false")
      }
      paint(node)
      box.addEventListener("mousedown", (e) => {
        e.preventDefault()
        if (!editor.isEditable) return
        const pos = typeof getPos === "function" ? getPos() : undefined
        if (pos === undefined) return
        editor.view.dispatch(editor.state.tr.setNodeMarkup(pos, undefined, { ...current.attrs, checked: !current.attrs.checked }))
      })
      return {
        dom,
        contentDOM: content,
        update: (n) => {
          if (n.type.name !== "para") return false
          paint(n)
          return true
        },
        ignoreMutation: (m) => m.target === box || box.contains(m.target as globalThis.Node),
        stopEvent: (e) => e.target === box,
      }
    }
  },
})

function typeFromTag(el: HTMLElement): BlockType {
  switch (el.tagName) {
    case "H1":
      return "HEADING"
    case "H2":
    case "H3":
    case "H4":
      return "SUBHEADING"
    case "LI":
      return el.parentElement?.tagName === "UL" && el.querySelector("input[type=checkbox]") ? "TASK" : "BULLET"
    default:
      return "BODY"
  }
}

/** Turns a media path into a URL to show, or null if the picture isn't available here yet. */
export type MediaResolver = (path: string) => string | null

/** A picture on its own line. */
export const Picture = Node.create<{ resolve: MediaResolver; open: (path: string) => void }>({
  name: "picture",
  group: "block",
  atom: true,
  selectable: true,
  draggable: false,

  addOptions() {
    return { resolve: () => null, open: () => {} }
  },

  addAttributes() {
    return {
      blockId: { default: null },
      image: { default: null },
      width: { default: 0.7 },
      align: { default: "START" },
    }
  },

  parseHTML() {
    return [{ tag: "figure[data-picture]" }]
  },

  renderHTML({ HTMLAttributes }) {
    return ["figure", mergeAttributes(HTMLAttributes, { "data-picture": "" })]
  },

  addNodeView() {
    const resolve = this.options.resolve
    const open = this.options.open
    return ({ node }) => {
      const dom = document.createElement("figure")
      dom.className = "picture"
      const paint = (n: PMNode) => {
        dom.dataset.align = n.attrs.align
        const image = n.attrs.image as { path?: string; aspect?: number } | null
        const width = Math.max(0.15, Math.min(1, Number(n.attrs.width) || 0.7))
        const aspect = Math.max(0.1, Number(image?.aspect) || 0.75)
        dom.replaceChildren()
        const frame = document.createElement("div")
        frame.className = "picture-frame"
        frame.style.width = `${width * 100}%`
        frame.style.aspectRatio = `${1 / aspect}`
        const url = image?.path ? resolve(image.path) : null
        if (url) {
          const img = document.createElement("img")
          img.src = url
          img.alt = ""
          img.loading = "lazy"
          img.title = "Open picture"
          img.style.cursor = "zoom-in"
          img.addEventListener("click", () => image?.path && open(image.path))
          frame.append(img)
        } else {
          frame.classList.add("missing")
          frame.title = "This picture hasn't synced yet"
        }
        dom.append(frame)
      }
      paint(node)
      return {
        dom,
        update: (n) => {
          if (n.type.name !== "picture") return false
          paint(n)
          return true
        },
      }
    }
  },
})

/** A line across the note. */
export const Divider = Node.create({
  name: "divider",
  group: "block",
  atom: true,
  selectable: true,
  addAttributes() {
    return { blockId: { default: null } }
  },
  parseHTML() {
    return [{ tag: "hr" }]
  },
  renderHTML({ HTMLAttributes }) {
    return ["hr", mergeAttributes(HTMLAttributes, { class: "divider" })]
  },
})

// ---- Marks ----

/** Highlight with the app's colors (ARGB hex); highlighted text is always dark, like in the app. */
export const Highlight = Mark.create({
  name: "highlight",
  addAttributes() {
    return {
      color: {
        default: null,
        parseHTML: (el) => el.getAttribute("data-color"),
        renderHTML: (a) => ({
          "data-color": a.color,
          style: `background-color: ${cssColor(a.color as string) ?? "#FFE14D"}; color: #111114`,
        }),
      },
    }
  },
  parseHTML() {
    return [{ tag: "mark" }]
  },
  renderHTML({ HTMLAttributes }) {
    return ["mark", HTMLAttributes, 0]
  },
})

/** Ink color and font. */
export const TextStyle = Mark.create({
  name: "textStyle",
  addAttributes() {
    return {
      color: {
        default: null,
        parseHTML: (el) => el.getAttribute("data-color"),
        renderHTML: (a) => (a.color ? { "data-color": a.color, style: `color: ${cssColor(a.color as string)}` } : {}),
      },
      font: {
        default: null,
        parseHTML: (el) => el.getAttribute("data-font"),
        renderHTML: (a) => {
          if (!a.font) return {}
          const css = cssFont(a.font as string)
          return css ? { "data-font": a.font, style: `font-family: ${css}` } : { "data-font": a.font }
        },
      },
    }
  },
  parseHTML() {
    return [{ tag: "span[data-color]" }, { tag: "span[data-font]" }]
  },
  renderHTML({ HTMLAttributes }) {
    return ["span", HTMLAttributes, 0]
  },
})

// ---- Paragraph commands ----

/** The paragraphs the selection touches, with their positions. */
export function selectedParas(state: EditorState): { node: PMNode; pos: number }[] {
  const out: { node: PMNode; pos: number }[] = []
  const { from, to } = state.selection
  state.doc.nodesBetween(from, to, (node, pos) => {
    if (node.type.name === "para") out.push({ node, pos })
    return false
  })
  if (out.length === 0) {
    const $from = state.selection.$from
    const node = $from.node(1)
    if (node?.type.name === "para") out.push({ node, pos: $from.before(1) })
  }
  return out
}

function updateParas(editor: Editor, change: (attrs: Record<string, unknown>, node: PMNode) => Record<string, unknown> | null) {
  syncSelection(editor)
  const { state } = editor
  let tr = state.tr
  let changed = false
  for (const { node, pos } of selectedParas(state)) {
    const next = change({ ...node.attrs }, node)
    if (next) {
      tr = tr.setNodeMarkup(pos, undefined, next)
      changed = true
    }
  }
  if (changed) editor.view.dispatch(tr)
  editor.view.focus()
}

/** The block type of the paragraph with the cursor. */
export function currentType(state: EditorState): string {
  return selectedParas(state)[0]?.node.attrs.blockType ?? "BODY"
}

export function currentAlign(state: EditorState): Align {
  return (selectedParas(state)[0]?.node.attrs.align as Align) ?? "START"
}

/** Applies [type] to the selected paragraphs, or turns them back to body text if they all have it. */
export function toggleBlockType(editor: Editor, type: BlockType) {
  const all = selectedParas(editor.state).every((p) => p.node.attrs.blockType === type)
  setBlockType(editor, all ? "BODY" : type)
}

export function setBlockType(editor: Editor, type: BlockType) {
  updateParas(editor, (a) => ({ ...a, blockType: type, checked: type === "TASK" ? a.checked : false }))
}

export function setAlign(editor: Editor, align: Align) {
  updateParas(editor, (a) => ({ ...a, align }))
}

export function cycleAlign(editor: Editor) {
  const order: Align[] = ["START", "CENTER", "END", "JUSTIFY"]
  const next = order[(order.indexOf(currentAlign(editor.state)) + 1) % order.length]
  setAlign(editor, next)
}

export function indent(editor: Editor, delta: number) {
  updateParas(editor, (a) => {
    const v = Math.max(0, Math.min(MAX_INDENT, Number(a.indent ?? 0) + delta))
    return v === a.indent ? null : { ...a, indent: v }
  })
}

/** Checks the selected tasks, or unchecks them if they're all checked. */
export function toggleTasks(editor: Editor) {
  const tasks = selectedParas(editor.state).filter((p) => p.node.attrs.blockType === "TASK")
  if (!tasks.length) return
  const all = tasks.every((p) => p.node.attrs.checked)
  updateParas(editor, (a) => (a.blockType === "TASK" ? { ...a, checked: !all } : null))
}

/** Adds a divider under the paragraph with the cursor (or turns an empty line into one), with a line after it. */
export function insertDivider(editor: Editor) {
  const { state } = editor
  const $from = state.selection.$from
  const para = $from.node(1)
  if (!para) return
  const start = $from.before(1)
  const end = $from.after(1)
  const divider = state.schema.nodes.divider.create({ blockId: newId() })
  let tr = state.tr
  let after: number
  if (para.type.name === "para" && para.content.size === 0) {
    tr = tr.replaceWith(start, end, divider)
    after = start + divider.nodeSize
  } else {
    tr = tr.insert(end, divider)
    after = end + divider.nodeSize
  }
  const next = tr.doc.nodeAt(after)
  if (!next || next.type.name !== "para") {
    tr = tr.insert(after, state.schema.nodes.para.create({ blockId: newId() }))
  }
  tr = tr.setSelection(TextSelection.create(tr.doc, after + 1))
  editor.view.dispatch(tr.scrollIntoView())
  editor.view.focus()
}

// ---- Keys and housekeeping ----

/**
 * Makes the editor pick up a cursor move the browser made itself (End, Home, Shift+arrows) but hasn't
 * reported yet, so a key pressed right after it acts at the right place.
 */
export function syncSelection(editor: Editor) {
  try {
    ;(editor.view as unknown as { domObserver?: { flush?: () => void } }).domObserver?.flush?.()
  } catch {
    // Nothing to catch up on.
  }
}

/** Typing rules that match the app (Enter, Backspace, Tab) and its keyboard shortcuts. */
export const QuartzKeys = Extension.create({
  name: "quartzKeys",
  // Ahead of the other extensions, so every shortcut sees the up-to-date cursor.
  priority: 1000,

  addKeyboardShortcuts() {
    const editor = this.editor
    return {
      Enter: () => enter(editor),
      Backspace: () => backspaceAtStart(editor),
      Tab: () => {
        indent(editor, 1)
        return true
      },
      "Shift-Tab": () => {
        indent(editor, -1)
        return true
      },
      "Mod-]": () => {
        indent(editor, 1)
        return true
      },
      "Mod-[": () => {
        indent(editor, -1)
        return true
      },
      "Mod-Shift-8": () => {
        toggleBlockType(editor, "BULLET")
        return true
      },
      "Mod-Shift-l": () => {
        toggleBlockType(editor, "TASK")
        return true
      },
      "Mod-Shift-u": () => {
        toggleTasks(editor)
        return true
      },
      "Mod-Shift-h": () => {
        setBlockType(editor, "HEADING")
        return true
      },
      "Mod-Shift-j": () => {
        setBlockType(editor, "SUBHEADING")
        return true
      },
      "Mod-Shift-b": () => {
        setBlockType(editor, "BODY")
        return true
      },
      "Mod-Shift-x": () => editor.commands.toggleMark("strike"),
    }
  },

  addProseMirrorPlugins() {
    const editor = this.editor
    const catchUp = new Plugin({
      key: new PluginKey("catchUpSelection"),
      props: {
        handleKeyDown: () => {
          syncSelection(editor)
          return false
        },
      },
    })
    return [catchUp, uniqueIds]
  },
})

/**
 * Enter: an empty list item ends the list; otherwise split, with the new paragraph keeping lists
 * going and headings followed by body text. At the very start of a line, a plain line goes above.
 */
function enter(editor: Editor): boolean {
  syncSelection(editor)
  const { state } = editor
  const sel = state.selection
  const $from = sel.$from
  const para = $from.parent
  if (para.type.name !== "para") return false
  const type = para.attrs.blockType as string
  if (sel.empty && para.content.size === 0 && LIST_TYPES.includes(type)) {
    editor.view.dispatch(state.tr.setNodeMarkup($from.before(), undefined, { ...para.attrs, blockType: "BODY", checked: false }))
    return true
  }
  let tr = state.tr
  if (!sel.empty) tr = tr.deleteSelection()
  const $pos = tr.selection.$from
  const atStart = $pos.parentOffset === 0 && $pos.parent.content.size > 0
  const keep = { align: para.attrs.align, indent: para.attrs.indent }
  if (atStart) {
    // A plain line above; this paragraph keeps its look.
    const above = state.schema.nodes.para.create({ blockId: newId(), blockType: LIST_TYPES.includes(type) ? type : "BODY", ...keep })
    tr = tr.insert($pos.before(), above)
  } else {
    const nextType = type === "HEADING" || type === "SUBHEADING" ? "BODY" : type
    tr = tr.split($pos.pos, 1, [{ type: state.schema.nodes.para, attrs: { blockId: newId(), blockType: nextType, checked: false, ...keep } }])
  }
  editor.view.dispatch(tr.scrollIntoView())
  return true
}

/** Backspace at the start of a heading, list item or task first turns it into body text. */
function backspaceAtStart(editor: Editor): boolean {
  syncSelection(editor)
  const { state } = editor
  const sel = state.selection
  if (!sel.empty) return false
  const $from = sel.$from
  const para = $from.parent
  if (para.type.name !== "para" || $from.parentOffset !== 0) return false
  if (para.attrs.blockType !== "BODY") {
    editor.view.dispatch(state.tr.setNodeMarkup($from.before(), undefined, { ...para.attrs, blockType: "BODY", checked: false }))
    return true
  }
  if (para.attrs.indent > 0) {
    editor.view.dispatch(state.tr.setNodeMarkup($from.before(), undefined, { ...para.attrs, indent: para.attrs.indent - 1 }))
    return true
  }
  return false
}

/** Every block keeps a unique id (pasted or split blocks get new ones). */
const uniqueIds = new Plugin({
  key: new PluginKey("uniqueBlockIds"),
  appendTransaction(trs: readonly Transaction[], _old, state) {
    if (!trs.some((t) => t.docChanged)) return null
    const seen = new Set<string>()
    let tr: Transaction | null = null
    state.doc.forEach((node, offset) => {
      const id = node.attrs.blockId as string | null
      if (!id || seen.has(id)) {
        tr = (tr ?? state.tr).setNodeAttribute(offset, "blockId", newId())
      } else seen.add(id)
    })
    return tr
  },
})

/** Everything the web editor needs. */
export function editorExtensions(opts: { placeholder?: string; resolveMedia?: MediaResolver; openPicture?: (path: string) => void } = {}) {
  return [
    Doc,
    Text,
    Para,
    Picture.configure({ resolve: opts.resolveMedia ?? (() => null), open: opts.openPicture ?? (() => {}) }),
    Divider,
    Bold,
    Italic,
    Underline,
    Strike,
    Highlight,
    TextStyle,
    UndoRedo,
    QuartzKeys,
    Placeholder.configure({ placeholder: opts.placeholder ?? "", showOnlyWhenEditable: true }),
  ]
}
