// The app's rich text format (RichDoc, see app/.../data/rich/RichDoc.kt) and its conversion to and
// from the web editor's document. A RichDoc is a flat list of paragraphs ("blocks"), each with a type,
// alignment, indent, checkbox state and character style ranges ("spans"). The editor uses the same
// flat shape, so nothing is lost either way.

export type BlockType = "HEADING" | "SUBHEADING" | "BODY" | "BULLET" | "TASK" | "IMAGE" | "DIVIDER"
export type Align = "START" | "CENTER" | "END" | "JUSTIFY"
export type InlineStyle = "BOLD" | "ITALIC" | "UNDERLINE" | "STRIKE" | "HIGHLIGHT" | "COLOR" | "FONT"

export const STYLE_ORDER: InlineStyle[] = ["BOLD", "ITALIC", "UNDERLINE", "STRIKE", "HIGHLIGHT", "COLOR", "FONT"]
export const VALUED: InlineStyle[] = ["HIGHLIGHT", "COLOR", "FONT"]

export interface StyleSpan {
  start: number
  end: number
  style: InlineStyle
  value?: string | null
}

/** A picture's details (ImageData in the app). Extra fields are kept as they are. */
export interface ImageData {
  path: string
  aspect: number
  [key: string]: unknown
}

export interface Block {
  id: string
  type?: BlockType
  text?: string
  spans?: StyleSpan[]
  align?: Align
  checked?: boolean
  indent?: number
  image?: ImageData | null
  imageWidth?: number
}

export interface RichDoc {
  blocks: Block[]
}

export const DEFAULT_IMAGE_WIDTH = 0.7

export function newId(): string {
  return crypto.randomUUID()
}

/** Reads a stored note body (JSON object, JSON text, plain text or nothing). */
export function decodeDoc(raw: unknown): RichDoc {
  let value = raw
  if (typeof value === "string") {
    if (value.trim() === "") return emptyDoc()
    try {
      value = JSON.parse(value)
    } catch {
      return fromPlainText(value as string)
    }
  }
  if (!value || typeof value !== "object" || !Array.isArray((value as RichDoc).blocks)) return emptyDoc()
  const blocks = (value as RichDoc).blocks.filter((b) => b && typeof b === "object").map((b) => ({ ...b, id: b.id || newId() }))
  return blocks.length ? { blocks } : emptyDoc()
}

export function emptyDoc(): RichDoc {
  return { blocks: [{ id: newId() }] }
}

export function fromPlainText(text: string): RichDoc {
  return { blocks: text.split("\n").map((t) => ({ id: newId(), ...(t ? { text: t } : {}) })) }
}

/** Search text, the same as the app's (block texts joined by line breaks). */
export function plainText(doc: RichDoc): string {
  return doc.blocks.map((b) => b.text ?? "").join("\n")
}

/**
 * Merges overlapping or touching spans of the same style and value, drops empty ones, and sorts by
 * start then style (the app's SpanOps.normalize).
 */
export function normalizeSpans(spans: StyleSpan[]): StyleSpan[] {
  const groups = new Map<string, StyleSpan[]>()
  for (const s of spans) {
    if (s.end <= s.start) continue
    const key = `${s.style}\u0000${s.value ?? ""}`
    let g = groups.get(key)
    if (!g) groups.set(key, (g = []))
    g.push({ ...s })
  }
  const out: StyleSpan[] = []
  for (const g of groups.values()) {
    g.sort((a, b) => a.start - b.start)
    let cur = g[0]
    for (let i = 1; i < g.length; i++) {
      const next = g[i]
      if (next.start <= cur.end) cur = { ...cur, end: Math.max(cur.end, next.end) }
      else {
        out.push(cur)
        cur = next
      }
    }
    out.push(cur)
  }
  return out.sort((a, b) => a.start - b.start || STYLE_ORDER.indexOf(a.style) - STYLE_ORDER.indexOf(b.style))
}

/** A block written the way the app writes it: defaults left out. */
export function compactBlock(b: Block): Block {
  const out: Block = { id: b.id }
  const type = b.type ?? "BODY"
  if (type !== "BODY") out.type = type
  if (b.text) out.text = b.text
  const spans = b.spans ? normalizeSpans(b.spans).map(compactSpan) : []
  if (spans.length) out.spans = spans
  if (b.align && b.align !== "START") out.align = b.align
  if (b.checked) out.checked = true
  if (b.indent) out.indent = b.indent
  if (b.image) out.image = b.image
  if (b.imageWidth !== undefined && b.imageWidth !== DEFAULT_IMAGE_WIDTH) out.imageWidth = b.imageWidth
  return out
}

function compactSpan(s: StyleSpan): StyleSpan {
  const out: StyleSpan = { start: s.start, end: s.end, style: s.style }
  if (s.value !== undefined && s.value !== null) out.value = s.value
  return out
}

export function compactDoc(doc: RichDoc): RichDoc {
  return { blocks: doc.blocks.map(compactBlock) }
}

// ---- Editor documents (ProseMirror JSON) ----

export interface PMMark {
  type: string
  attrs?: Record<string, unknown>
}
export interface PMNode {
  type: string
  attrs?: Record<string, unknown>
  content?: PMNode[]
  text?: string
  marks?: PMMark[]
}

/** RichDoc → editor document. */
export function toEditor(doc: RichDoc): PMNode {
  const content: PMNode[] = doc.blocks.map((b) => {
    const type = b.type ?? "BODY"
    if (type === "IMAGE" && b.image) {
      return {
        type: "picture",
        attrs: { blockId: b.id, image: b.image, width: b.imageWidth ?? DEFAULT_IMAGE_WIDTH, align: b.align ?? "START" },
      }
    }
    if (type === "DIVIDER") return { type: "divider", attrs: { blockId: b.id } }
    const node: PMNode = {
      type: "para",
      attrs: {
        blockId: b.id,
        blockType: type === "IMAGE" ? "BODY" : type,
        align: b.align ?? "START",
        checked: !!b.checked,
        indent: b.indent ?? 0,
      },
    }
    const text = b.text ?? ""
    if (text) node.content = textNodes(text, b.spans ?? [])
    return node
  })
  return { type: "doc", content: content.length ? content : [{ type: "para", attrs: { blockId: newId() } }] }
}

function textNodes(text: string, spans: StyleSpan[]): PMNode[] {
  const cuts = new Set<number>([0, text.length])
  for (const s of spans) {
    cuts.add(clamp(s.start, 0, text.length))
    cuts.add(clamp(s.end, 0, text.length))
  }
  const points = [...cuts].sort((a, b) => a - b)
  const out: PMNode[] = []
  for (let i = 0; i + 1 < points.length; i++) {
    const a = points[i]
    const b = points[i + 1]
    if (a >= b) continue
    const active = spans.filter((s) => s.start <= a && s.end >= b)
    const marks = marksFor(active)
    out.push(marks.length ? { type: "text", text: text.slice(a, b), marks } : { type: "text", text: text.slice(a, b) })
  }
  return out
}

function marksFor(spans: StyleSpan[]): PMMark[] {
  const marks: PMMark[] = []
  const has = (s: InlineStyle) => spans.find((x) => x.style === s)
  if (has("BOLD")) marks.push({ type: "bold" })
  if (has("ITALIC")) marks.push({ type: "italic" })
  if (has("UNDERLINE")) marks.push({ type: "underline" })
  if (has("STRIKE")) marks.push({ type: "strike" })
  const hl = has("HIGHLIGHT")
  if (hl) marks.push({ type: "highlight", attrs: { color: hl.value ?? null } })
  const color = has("COLOR")
  const font = has("FONT")
  if (color || font) marks.push({ type: "textStyle", attrs: { color: color?.value ?? null, font: font?.value ?? null } })
  return marks
}

/** Editor document → RichDoc. */
export function fromEditor(json: PMNode): RichDoc {
  const blocks: Block[] = []
  for (const node of json.content ?? []) {
    const a = node.attrs ?? {}
    const id = (a.blockId as string) || newId()
    if (node.type === "picture") {
      blocks.push(
        compactBlock({
          id,
          type: "IMAGE",
          image: a.image as ImageData,
          imageWidth: (a.width as number) ?? DEFAULT_IMAGE_WIDTH,
          align: (a.align as Align) ?? "START",
        }),
      )
      continue
    }
    if (node.type === "divider") {
      blocks.push({ id, type: "DIVIDER" })
      continue
    }
    let text = ""
    const spans: StyleSpan[] = []
    for (const t of node.content ?? []) {
      const piece = t.type === "text" ? (t.text ?? "") : t.type === "hardBreak" ? " " : ""
      const start = text.length
      text += piece
      const end = text.length
      for (const m of t.marks ?? []) {
        switch (m.type) {
          case "bold":
            spans.push({ start, end, style: "BOLD" })
            break
          case "italic":
            spans.push({ start, end, style: "ITALIC" })
            break
          case "underline":
            spans.push({ start, end, style: "UNDERLINE" })
            break
          case "strike":
            spans.push({ start, end, style: "STRIKE" })
            break
          case "highlight":
            spans.push({ start, end, style: "HIGHLIGHT", value: (m.attrs?.color as string) ?? null })
            break
          case "textStyle":
            if (m.attrs?.color) spans.push({ start, end, style: "COLOR", value: m.attrs.color as string })
            if (m.attrs?.font) spans.push({ start, end, style: "FONT", value: m.attrs.font as string })
            break
        }
      }
    }
    const type = (a.blockType as BlockType) ?? "BODY"
    blocks.push(
      compactBlock({
        id,
        type,
        text,
        spans,
        align: (a.align as Align) ?? "START",
        checked: type === "TASK" && !!a.checked,
        indent: (a.indent as number) ?? 0,
      }),
    )
  }
  return blocks.length ? { blocks } : emptyDoc()
}

function clamp(v: number, lo: number, hi: number) {
  return Math.max(lo, Math.min(hi, v))
}

// ---- Colors ----

/** The app stores colors as hex ARGB ("FFE14D", "#FFE14D" or "FFFFE14D"); CSS wants #RRGGBB(AA). */
export function cssColor(hex: string | null | undefined): string | null {
  if (!hex) return null
  const clean = hex.replace(/^#/, "")
  if (/^[0-9a-fA-F]{6}$/.test(clean)) return `#${clean}`
  if (/^[0-9a-fA-F]{8}$/.test(clean)) return `#${clean.slice(2)}${clean.slice(0, 2)}`
  return null
}

/** A signed 32-bit ARGB number (how the app stores colors in records) → CSS. */
export function argbToCss(argb: number | null | undefined): string | null {
  if (argb === null || argb === undefined) return null
  const v = argb >>> 0
  const a = (v >>> 24) & 0xff
  const r = (v >>> 16) & 0xff
  const g = (v >>> 8) & 0xff
  const b = v & 0xff
  return a === 0xff ? `rgb(${r} ${g} ${b})` : `rgb(${r} ${g} ${b} / ${(a / 255).toFixed(3)})`
}

/** Imported fonts ("file:<name>") that have been loaded, → their CSS family name. */
const importedFonts = new Map<string, string>()

export function registerFont(id: string, family: string) {
  importedFonts.set(id, family)
}

/** Font ids → CSS. Imported fonts the browser hasn't got fall back to the page font. */
export function cssFont(id: string | null | undefined): string | null {
  if (id && importedFonts.has(id)) return `"${importedFonts.get(id)}", system-ui, sans-serif`
  switch (id) {
    case "sans":
      return "system-ui, sans-serif"
    case "serif":
      return "Georgia, 'Times New Roman', serif"
    case "mono":
      return "ui-monospace, 'SFMono-Regular', Menlo, Consolas, monospace"
    case "cursive":
      return "'Segoe Script', 'Bradley Hand', 'Comic Sans MS', cursive"
    default:
      return null
  }
}

/** Pieces of a block's text with the CSS for their character styles (for read-only views). */
export function styledSegments(text: string, spans: StyleSpan[]): { text: string; css: string }[] {
  if (!text) return []
  const cuts = new Set<number>([0, text.length])
  for (const s of spans) {
    cuts.add(clamp(s.start, 0, text.length))
    cuts.add(clamp(s.end, 0, text.length))
  }
  const points = [...cuts].sort((a, b) => a - b)
  const out: { text: string; css: string }[] = []
  for (let i = 0; i + 1 < points.length; i++) {
    const a = points[i]
    const b = points[i + 1]
    if (a >= b) continue
    const on = spans.filter((s) => s.start <= a && s.end >= b)
    const has = (st: InlineStyle) => on.find((s) => s.style === st)
    const css: string[] = []
    if (has("BOLD")) css.push("font-weight:700")
    if (has("ITALIC")) css.push("font-style:italic")
    const font = cssFont(has("FONT")?.value)
    if (font) css.push(`font-family:${font}`)
    const hl = has("HIGHLIGHT")
    if (hl) css.push(`background:${cssColor(hl.value) ?? "#FFE14D"};color:#111114;border-radius:3px`)
    const color = cssColor(has("COLOR")?.value)
    if (color) css.push(`color:${color}`)
    const deco = [has("UNDERLINE") ? "underline" : "", has("STRIKE") ? "line-through" : ""].filter(Boolean).join(" ")
    if (deco) css.push(`text-decoration:${deco}`)
    out.push({ text: text.slice(a, b), css: css.join(";") })
  }
  return out
}

/** Short preview lines, like the app's note cards (list markers, empty lines skipped). */
export function previewLines(doc: RichDoc, max = 8): string[] {
  const out: string[] = []
  for (const b of doc.blocks) {
    const t = (b.text ?? "").trim()
    if (!t) continue
    out.push(b.type === "BULLET" ? `• ${t}` : b.type === "TASK" ? `${b.checked ? "☑" : "☐"} ${t}` : t)
    if (out.length >= max) break
  }
  return out
}
