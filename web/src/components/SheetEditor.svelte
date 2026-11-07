<script lang="ts">
  // A sheet page's grid on the web, working like the app's (docs/sheet-format.md): click to select
  // (drag or Shift+click for more), double-click or type to fill a cell, the formula bar, the tools,
  // and the usual keys.
  import { onDestroy, tick, untrack } from "svelte"
  import Icon from "./Icon.svelte"
  import { luminance } from "../lib/scrapbook"
  import {
    type Area,
    type Block,
    type Borders,
    type SheetData,
    type SheetStyle,
    SheetCalc,
    TABLE_STYLES,
    addr,
    areaLabel,
    areaOf,
    areaStats,
    autoSum,
    blockText,
    clearArea,
    closeParens,
    colName,
    columnValues,
    copyBlock,
    deleteCols,
    deleteRows,
    fillDown,
    fillRight,
    general,
    hiddenRows,
    insertCols,
    insertRows,
    pasteBlock,
    pasteText,
    setBorders,
    sortRows,
    typeInto,
    updateStyles,
    usedSize,
    valuedSize,
    colHidden,
    handHiddenRows,
    hideCols,
    hideRows,
    unhideCols,
    unhideRows,
    unhideAll,
    toXlsx,
    toCsv,
  } from "../lib/sheet"
  import { INK_PRESETS } from "../lib/palette"

  import type { Snippet } from "svelte"

  let {
    sheet: initial,
    ink,
    paper,
    locked,
    onchange,
    children,
    title = "",
  }: { sheet: SheetData; ink: string; paper: string; locked: boolean; onchange: (s: SheetData) => void; children?: Snippet; title?: string } = $props()

  let data: SheetData = $state.raw(untrack(() => initial))
  let clock = $state(0)
  const calc = $derived.by(() => {
    clock
    return new SheetCalc(data)
  })
  const timer = setInterval(() => clock++, 60_000)
  onDestroy(() => clearInterval(timer))

  const hidden = $derived(hiddenRows(data, calc))
  const used = $derived(usedSize(data))
  const valued = $derived(valuedSize(data))
  let anchor = $state<[number, number]>([0, 0])
  let focus = $state<[number, number]>([0, 0])
  // Whole columns (or rows) picked from a header: the corners stay in the first row (column), so
  // nothing scrolls along them, and the area stretches to the end however far that grows.
  let whole = $state<"cols" | "rows" | null>(null)
  const area = $derived.by(() => {
    const a = areaOf(anchor, focus)
    if (whole === "cols") return { ...a, r1: 0, r2: rows - 1 }
    if (whole === "rows") return { ...a, c1: 0, c2: cols - 1 }
    return a
  })
  let editing = $state<string | null>(null)
  let editCell = $state<[number, number]>([0, 0])
  let extraRows = $state(0)
  let extraCols = $state(0)
  const rows = $derived(Math.max(data.rows, used[0] + 10, focus[0] + 10) + extraRows)
  // Enough columns to fill the width there is, like a spreadsheet app (only shown, not saved).
  let gridWidth = $state(0)
  const fillCols = $derived.by(() => {
    let x = 44
    let c = 0
    while (x < gridWidth && c < 702) {
      if (!colHidden(data, c)) x += data.widths[colName(c)] ?? 100
      c++
    }
    return c
  })
  const cols = $derived(Math.max(data.cols, used[1] + 3, focus[1] + 3, fillCols) + extraCols)
  const rowList = $derived(Array.from({ length: rows }, (_, i) => i))
  // Hidden columns aren't drawn at all; a double line marks where they are.
  const colList = $derived(Array.from({ length: cols }, (_, i) => i).filter((c) => !colHidden(data, c)))
  const handHidden = $derived(handHiddenRows(data))

  // ---- Undo, and changes from elsewhere ----

  let undoStack: SheetData[] = []
  let redoStack: SheetData[] = []
  let canUndo = $state(false)
  let canRedo = $state(false)

  function change(next: SheetData) {
    if (locked || JSON.stringify(next) === JSON.stringify(data)) return
    undoStack.push(data)
    if (undoStack.length > 100) undoStack.shift()
    redoStack = []
    data = next
    canUndo = true
    canRedo = false
    onchange(next)
  }
  function undo() {
    const prev = undoStack.pop()
    if (!prev || locked) return
    redoStack.push(data)
    data = prev
    canUndo = undoStack.length > 0
    canRedo = true
    onchange(prev)
  }
  function redo() {
    const next = redoStack.pop()
    if (!next || locked) return
    undoStack.push(data)
    data = next
    canUndo = true
    canRedo = redoStack.length > 0
    onchange(next)
  }

  /** Shows another device's version of the sheet. */
  export function load(next: SheetData) {
    undoStack.push(data)
    redoStack = []
    data = next
    canUndo = true
    canRedo = false
  }

  // ---- Selection ----

  function select(r: number, c: number, extend = false) {
    const cell: [number, number] = [Math.max(0, r), Math.max(0, c)]
    whole = null
    if (!extend) anchor = cell
    focus = cell
  }
  function selectArea(a: Area) {
    whole = null
    anchor = [a.r1, a.c1]
    focus = [a.r2, a.c2]
  }
  function selectCols(c1: number, c2: number) {
    whole = "cols"
    anchor = [0, c1]
    focus = [0, c2]
  }
  function selectRows(r1: number, r2: number) {
    whole = "rows"
    anchor = [r1, 0]
    focus = [r2, 0]
  }
  function moveBy(dr: number, dc: number, extend = false) {
    let r = focus[0]
    const step = dr > 0 ? 1 : -1
    for (let i = 0; i < Math.abs(dr); i++) {
      let next = r + step
      while (hidden.has(next)) next += step
      if (next >= 0) r = next
    }
    let c = focus[1]
    const cstep = dc > 0 ? 1 : -1
    for (let i = 0; i < Math.abs(dc); i++) {
      let next = c + cstep
      while (next >= 0 && colHidden(data, next)) next += cstep
      if (next >= 0) c = next
    }
    select(r, c, extend)
  }
  const inArea = (r: number, c: number) => r >= area.r1 && r <= area.r2 && c >= area.c1 && c <= area.c2

  // ---- Typing ----

  let gridEl: HTMLDivElement | undefined = $state()
  let cellInput: HTMLInputElement | undefined = $state()
  let barInput: HTMLInputElement | undefined = $state()
  /** A reference put into a formula by clicking a cell: where it sits in the text. */
  let insertedRef: [number, number] | null = null

  function editText(r: number, c: number): string {
    const cell = data.cells[addr(r, c)]
    const v = cell?.v
    if (!v) return ""
    if (v.startsWith("=") || cell.s?.fmt === "text") return v
    const value = calc.value(r, c)
    if (value.k !== "num") return v
    const fmt = cell.s?.fmt
    if (fmt === "percent") return general(value.n * 100) + "%"
    if (fmt === "date" || fmt === "time" || fmt === "datetime") return calc.display(r, c)
    return general(value.n)
  }

  async function startEdit(text?: string, into: "cell" | "bar" = "cell") {
    if (locked) return
    whole = null
    focus = anchor
    editCell = anchor
    editing = text ?? editText(anchor[0], anchor[1])
    insertedRef = null
    await tick()
    const input = into === "cell" ? cellInput : barInput
    input?.focus()
    input?.setSelectionRange(editing.length, editing.length)
  }

  function commitEdit(dr = 1, dc = 0) {
    if (editing === null) return
    const text = editing
    const [r, c] = editCell
    editing = null
    insertedRef = null
    change(typeInto(data, r, c, closeParens(text)))
    select(r, c)
    if (dr || dc) moveBy(dr, dc)
    gridEl?.focus({ preventScroll: true })
  }

  function cancelEdit() {
    editing = null
    insertedRef = null
    select(editCell[0], editCell[1])
    gridEl?.focus({ preventScroll: true })
  }

  /** The input being typed in (cell or bar). */
  function activeInput(): HTMLInputElement | undefined {
    return document.activeElement === barInput ? barInput : cellInput
  }

  /** True when clicking a cell now should put its reference into the formula being typed. */
  function wantsReference(): boolean {
    if (editing === null || !editing.startsWith("=")) return false
    const input = activeInput()
    const at = input?.selectionStart ?? editing.length
    if (insertedRef && at === insertedRef[1]) return true
    const before = editing.slice(0, at).trimEnd()
    return before.length > 0 && "=+-*/^&(,:<>".includes(before[before.length - 1])
  }

  async function insertReference(a: Area) {
    if (editing === null) return
    const input = activeInput()
    const ref = areaLabel(a)
    const selStart = input?.selectionStart ?? editing.length
    const selEnd = input?.selectionEnd ?? editing.length
    const [from, to] = insertedRef && selStart === insertedRef[1] ? insertedRef : [selStart, selEnd]
    editing = editing.slice(0, from) + ref + editing.slice(to)
    insertedRef = [from, from + ref.length]
    await tick()
    input?.focus()
    input?.setSelectionRange(from + ref.length, from + ref.length)
  }

  function inputKey(e: KeyboardEvent) {
    // Keys typed into a cell are the cell's, not the grid's.
    e.stopPropagation()
    if (e.key === "Enter") {
      e.preventDefault()
      commitEdit(e.shiftKey ? -1 : 1, 0)
    } else if (e.key === "Tab") {
      e.preventDefault()
      commitEdit(0, e.shiftKey ? -1 : 1)
    } else if (e.key === "Escape") {
      e.preventDefault()
      cancelEdit()
    }
  }

  // ---- Working on the selection ----

  const current: SheetStyle = $derived(data.cells[addr(anchor[0], anchor[1])]?.s ?? {})
  const style = (f: (s: SheetStyle) => SheetStyle) => change(updateStyles(data, area, f))
  let clip: Block | null = null
  let clipText: string | null = null

  function copyText(): string {
    clip = copyBlock(data, area)
    clipText = blockText(data, area, calc)
    return clipText
  }
  function paste(text: string | null) {
    const [r, c] = anchor
    if (clip && (text === null || text === clipText)) {
      change(pasteBlock(data, clip, r, c))
      selectArea({ r1: r, c1: c, r2: r + clip.rows - 1, c2: c + clip.cols - 1 })
    } else if (text) {
      change(pasteText(data, text, r, c))
      const lines = text.replace(/\n+$/, "").split("\n")
      selectArea({ r1: r, c1: c, r2: r + lines.length - 1, c2: c + Math.max(...lines.map((l) => l.split("\t").length)) - 1 })
    }
  }
  async function pasteFromClipboard() {
    let text: string | null = null
    try {
      text = await navigator.clipboard.readText()
    } catch {
      text = null
    }
    paste(text)
  }
  async function copyToClipboard(cut = false) {
    const text = copyText()
    try {
      await navigator.clipboard.writeText(text)
    } catch {
      // The clipboard isn't available here: pasting in this sheet still works.
    }
    if (cut) change(clearArea(data, area, true))
  }

  function sort(desc: boolean) {
    const a = area
    const [from, to] = a.r2 > a.r1 && a.r2 < rows - 1 ? [a.r1, Math.min(a.r2, used[0] - 1)] : [1, used[0] - 1]
    if (to > from) change(sortRows(data, a.c1, desc, from, to))
  }
  function doAutoSum() {
    const [next, cell] = autoSum(data, area, calc)
    change(next)
    select(cell[0], cell[1])
  }
  function insertRowsAt(below: boolean) {
    const a = area
    const n = a.r2 - a.r1 + 1
    change(insertRows(data, below ? a.r2 + 1 : a.r1, n))
    if (below) selectArea({ ...a, r1: a.r2 + 1, r2: a.r2 + n })
  }
  function insertColsAt(right: boolean) {
    const a = area
    const n = a.c2 - a.c1 + 1
    change(insertCols(data, right ? a.c2 + 1 : a.c1, n))
    if (right) selectArea({ ...a, c1: a.c2 + 1, c2: a.c2 + n })
  }
  function removeRows() {
    change(deleteRows(data, area.r1, area.r2 - area.r1 + 1))
    select(area.r1, area.c1)
  }
  /** Hidden columns in the selection, or else right beside it (as they can't be selected). */
  function hiddenColsNear(): [number, number] | null {
    const a = area
    for (let c = a.c1; c <= a.c2; c++) if (colHidden(data, c)) return [a.c1, a.c2]
    let lo = a.c1
    while (lo > 0 && colHidden(data, lo - 1)) lo--
    let hi = a.c2
    while (colHidden(data, hi + 1)) hi++
    return lo < a.c1 || hi > a.c2 ? [lo, hi] : null
  }
  function hiddenRowsNear(): [number, number] | null {
    const a = area
    for (let r = a.r1; r <= a.r2; r++) if (handHidden.has(r)) return [a.r1, a.r2]
    let lo = a.r1
    while (lo > 0 && handHidden.has(lo - 1)) lo--
    let hi = a.r2
    while (handHidden.has(hi + 1)) hi++
    return lo < a.r1 || hi > a.r2 ? [lo, hi] : null
  }

  function removeCols() {
    change(deleteCols(data, area.c1, area.c2 - area.c1 + 1))
    select(area.r1, area.c1)
  }

  // ---- Keys ----

  function gridKey(e: KeyboardEvent) {
    if (editing !== null) return
    const cmd = e.ctrlKey || e.metaKey
    const shift = e.shiftKey
    const k = e.key
    let handled = true
    if (cmd) {
      const lower = k.toLowerCase()
      if (lower === "z") shift ? redo() : undo()
      else if (lower === "y") redo()
      else if (lower === "a") selectArea({ r1: 0, c1: 0, r2: Math.max(used[0], 1) - 1, c2: Math.max(used[1], 1) - 1 })
      else if (lower === "b") style((s) => ({ ...s, b: !current.b }))
      else if (lower === "i") style((s) => ({ ...s, i: !current.i }))
      else if (lower === "u") style((s) => ({ ...s, u: !current.u }))
      else if (lower === "d") change(fillDown(data, area))
      else if (lower === "r") change(fillRight(data, area))
      else if (k === "ArrowUp") select(0, focus[1], shift)
      else if (k === "ArrowDown") select(Math.max(used[0] - 1, 0), focus[1], shift)
      else if (k === "ArrowLeft") select(focus[0], 0, shift)
      else if (k === "ArrowRight") select(focus[0], Math.max(used[1] - 1, 0), shift)
      else handled = false // copy, cut and paste come as clipboard events
    } else if (e.altKey) {
      handled = false
    } else if (k === "ArrowUp") moveBy(-1, 0, shift)
    else if (k === "ArrowDown") moveBy(1, 0, shift)
    else if (k === "ArrowLeft") moveBy(0, -1, shift)
    else if (k === "ArrowRight") moveBy(0, 1, shift)
    else if (k === "Tab") moveBy(0, shift ? -1 : 1)
    else if (k === "Enter") moveBy(shift ? -1 : 1, 0)
    else if (k === "PageDown") moveBy(20, 0, shift)
    else if (k === "PageUp") moveBy(-20, 0, shift)
    else if (k === "Home") select(focus[0], 0, shift)
    else if (k === "F2") startEdit()
    else if (k === "Delete" || k === "Backspace") change(clearArea(data, area))
    else if (k.length === 1 && !locked) startEdit(k)
    else handled = false
    if (handled) e.preventDefault()
  }

  function onCopy(e: ClipboardEvent) {
    if (editing !== null) return
    e.preventDefault()
    e.clipboardData?.setData("text/plain", copyText())
  }
  function onCut(e: ClipboardEvent) {
    if (editing !== null || locked) return
    onCopy(e)
    change(clearArea(data, area, true))
  }
  function onPaste(e: ClipboardEvent) {
    if (editing !== null || locked) return
    e.preventDefault()
    paste(e.clipboardData?.getData("text/plain") ?? null)
  }

  // ---- Pointer ----

  type Press = { kind: "cells" | "rows" | "cols" | "ref"; start: [number, number]; touch: boolean; x: number; y: number; moved: boolean }
  let press: Press | null = null
  let lastClick = { at: 0, r: -1, c: -1 }

  function cellFrom(el: Element | null): [number, number] | null {
    const td = el?.closest("[data-r][data-c]") as HTMLElement | null
    if (!td) return null
    return [Number(td.dataset.r), Number(td.dataset.c)]
  }

  function pointerDown(e: PointerEvent) {
    if (e.button !== 0) return
    const target = e.target as HTMLElement
    if (target.closest(".resize") || target.closest("input")) return
    const colHead = target.closest("[data-col]") as HTMLElement | null
    const rowHead = target.closest("[data-row]") as HTMLElement | null
    const touch = e.pointerType === "touch"
    if (editing !== null && !touch) {
      const cell = cellFrom(target)
      if (cell && wantsReference()) {
        e.preventDefault()
        insertReference({ r1: cell[0], c1: cell[1], r2: cell[0], c2: cell[1] })
        press = { kind: "ref", start: cell, touch, x: e.clientX, y: e.clientY, moved: false }
        gridEl?.setPointerCapture(e.pointerId)
        return
      }
    }
    if (colHead) {
      if (editing !== null) commitEdit(0, 0)
      const c = Number(colHead.dataset.col)
      selectCols(e.shiftKey ? anchor[1] : c, c)
      press = { kind: "cols", start: [0, c], touch, x: e.clientX, y: e.clientY, moved: false }
    } else if (rowHead) {
      if (editing !== null) commitEdit(0, 0)
      const r = Number(rowHead.dataset.row)
      selectRows(e.shiftKey ? anchor[0] : r, r)
      press = { kind: "rows", start: [r, 0], touch, x: e.clientX, y: e.clientY, moved: false }
    } else {
      const cell = cellFrom(target)
      if (!cell) return
      press = { kind: "cells", start: cell, touch, x: e.clientX, y: e.clientY, moved: false }
      if (touch) return // a tap selects when the finger lifts; a drag scrolls
      if (editing !== null) commitEdit(0, 0)
      select(cell[0], cell[1], e.shiftKey)
    }
    if (!touch) gridEl?.setPointerCapture(e.pointerId)
    gridEl?.focus({ preventScroll: true })
  }

  function pointerMove(e: PointerEvent) {
    const p = press
    if (!p) return
    if (Math.hypot(e.clientX - p.x, e.clientY - p.y) > 6) p.moved = true
    if (p.touch) return
    const under = document.elementFromPoint(e.clientX, e.clientY)
    if (p.kind === "cols") {
      const head = under?.closest("[data-col]") as HTMLElement | null
      const cell = cellFrom(under)
      const c = head ? Number(head.dataset.col) : cell?.[1]
      if (c !== undefined) selectCols(anchor[1], c)
      return
    }
    if (p.kind === "rows") {
      const head = under?.closest("[data-row]") as HTMLElement | null
      const cell = cellFrom(under)
      const r = head ? Number(head.dataset.row) : cell?.[0]
      if (r !== undefined) selectRows(anchor[0], r)
      return
    }
    const cell = cellFrom(under)
    if (!cell) return
    if (p.kind === "ref") insertReference(areaOf(p.start, cell))
    else select(cell[0], cell[1], true)
  }

  function pointerUp(e: PointerEvent) {
    const p = press
    press = null
    if (!p || p.kind !== "cells") return
    const [r, c] = p.start
    const now = Date.now()
    const again = lastClick.r === r && lastClick.c === c && now - lastClick.at < 450
    lastClick = { at: now, r, c }
    if (p.touch) {
      if (p.moved) return
      if (editing !== null) {
        if (wantsReference()) {
          insertReference({ r1: r, c1: c, r2: r, c2: c })
          return
        }
        commitEdit(0, 0)
      }
      const wasSelected = area.r1 === area.r2 && area.c1 === area.c2 && anchor[0] === r && anchor[1] === c
      select(r, c)
      if (wasSelected || again) startEdit()
      return
    }
    if (again && !p.moved && !e.shiftKey) startEdit()
  }

  // Context menu (right-click or a long press).
  let menuAt = $state<{ x: number; y: number } | null>(null)
  function contextMenu(e: MouseEvent) {
    const cell = cellFrom(e.target as Element)
    if (!cell) return
    e.preventDefault()
    if (!inArea(cell[0], cell[1])) select(cell[0], cell[1])
    menuAt = { x: e.clientX, y: e.clientY }
  }

  // Widening a column by dragging its header's edge.
  let widening = $state<{ c: number; w: number } | null>(null)
  function startWiden(e: PointerEvent, c: number) {
    if (locked) return
    e.preventDefault()
    e.stopPropagation()
    const startX = e.clientX
    const startW = width(c)
    const el = e.currentTarget as HTMLElement
    el.setPointerCapture(e.pointerId)
    widening = { c, w: startW }
    const move = (ev: PointerEvent) => (widening = { c, w: Math.min(600, Math.max(36, startW + ev.clientX - startX)) })
    const up = () => {
      el.removeEventListener("pointermove", move)
      el.removeEventListener("pointerup", up)
      const w = widening?.w ?? startW
      widening = null
      if (Math.abs(w - startW) > 0.5) change({ ...data, widths: { ...data.widths, [colName(c)]: Math.round(w) } })
    }
    el.addEventListener("pointermove", move)
    el.addEventListener("pointerup", up)
  }
  const width = (c: number) => (widening?.c === c ? widening.w : (data.widths[colName(c)] ?? 100))

  // More rows and columns as the sheet is scrolled to its end.
  function onScroll(e: Event) {
    const el = e.currentTarget as HTMLElement
    if (el.scrollTop + el.clientHeight > el.scrollHeight - 200 && rows < 5000) extraRows += 50
    if (el.scrollLeft + el.clientWidth > el.scrollWidth - 200 && cols < 200) extraCols += 5
  }

  // Keeps the moving corner of the selection in view.
  $effect(() => {
    const [r, c] = focus
    const el = gridEl?.querySelector(`[data-r="${r}"][data-c="${c}"]`) as HTMLElement | null
    // A whole column selection doesn't move up or down (nor a whole row one sideways).
    const w = untrack(() => whole)
    if (!el || !gridEl) return
    const top = gridEl.scrollTop
    const left = gridEl.scrollLeft
    el.scrollIntoView({ block: "nearest", inline: "nearest" })
    if (w === "cols") gridEl.scrollTop = top
    if (w === "rows") gridEl.scrollLeft = left
  })

  // ---- How cells look ----

  const hexToArgb = (hex: string) => (0xff000000 | parseInt(hex.slice(1), 16)) | 0
  const textOn = (hex: string) => (luminance(hexToArgb(hex)) > 0.45 ? "#111114" : "#F2F2F5")

  function cellCss(r: number, c: number): string {
    const s: SheetStyle = data.cells[addr(r, c)]?.s ?? {}
    const table = data.table ? TABLE_STYLES[data.table.style] : undefined
    const inTable = !!table && r < valued[0] && c < valued[1]
    const header = inTable && r === 0
    const tableFill = !inTable ? undefined : header ? table!.header : r % 2 === 0 ? table!.band : "#FFFFFF"
    const fill = s.fill ?? tableFill
    const color = s.color ?? (header && !s.fill ? "#FFFFFF" : fill ? textOn(fill) : undefined)
    const value = calc.value(r, c)
    const align = s.align ?? (value.k === "num" ? "right" : value.k === "bool" || value.k === "err" ? "center" : "left")
    const parts = [`text-align:${align}`]
    if (fill) parts.push(`background-color:${fill}`)
    if (color) parts.push(`color:${color}`)
    if (s.b || header) parts.push("font-weight:700")
    if (s.i) parts.push("font-style:italic")
    if (s.u) parts.push("text-decoration:underline")
    if (s.wrap) parts.push("white-space:normal;overflow-wrap:anywhere")
    const b = s.border ?? ""
    const line = `1.5px solid ${ink}`
    if (b.includes("t")) parts.push(`border-top:${line}`)
    if (b.includes("b")) parts.push(`border-bottom:${line}`)
    if (b.includes("l")) parts.push(`border-left:${line}`)
    if (b.includes("r")) parts.push(`border-right:${line}`)
    return parts.join(";")
  }

  const stats = $derived(area.r1 === area.r2 && area.c1 === area.c2 ? null : areaStats(data, calc, area))
  const barText = $derived(editing ?? editText(anchor[0], anchor[1]))

  // ---- Toolbar menus ----

  let menu = $state<"color" | "fill" | "borders" | "align" | "format" | "sort" | "table" | "more" | null>(null)
  const FILLS = ["#FFF3B0", "#D9F5C8", "#D3E9FF", "#FFD9E6", "#FFE1C2", "#E6DAFF", "#EDEDF0", "#FFE14D", "#2F6FDE", "#2E9E5B", "#D6334C", "#111114"]
  const FORMATS: [string | undefined, string][] = [
    [undefined, "General"],
    ["number", "Number  1,234.50"],
    ["currency", "Currency  $1,234.50"],
    ["percent", "Percent  45%"],
    ["date", "Date  10/31/2026"],
    ["time", "Time  3:30 PM"],
    ["datetime", "Date and time"],
    ["text", "Plain text"],
  ]
  const BORDERS: [Borders, string][] = [
    ["all", "All borders"],
    ["outline", "Outline"],
    ["top", "Top"],
    ["bottom", "Bottom"],
    ["left", "Left"],
    ["right", "Right"],
    ["none", "No borders"],
  ]
  const defaultPlaces = (fmt?: string) => (fmt === "number" || fmt === "currency" ? 2 : 0)
  const inkHex = (c: string) => "#" + c.slice(2).toUpperCase()
  function places(delta: number) {
    style((s) => {
      const fmt = s.fmt && s.fmt !== "general" ? s.fmt : "number"
      return { ...s, fmt, dp: Math.min(10, Math.max(0, (s.dp ?? defaultPlaces(fmt)) + delta)) }
    })
  }

  // Filter window.
  let filterCol = $state<number | null>(null)
  let filterHidden = $state<string[]>([])
  const filterValues = $derived(filterCol === null ? [] : columnValues(data, filterCol, calc))
  function openFilter() {
    filterCol = area.c1
    filterHidden = [...(data.hide?.[colName(area.c1)] ?? [])]
  }
  function applyFilter() {
    if (filterCol === null) return
    const key = colName(filterCol)
    const hide = { ...(data.hide ?? {}) }
    if (filterHidden.length) hide[key] = [...filterHidden]
    else delete hide[key]
    const next = { ...data }
    if (Object.keys(hide).length) next.hide = hide
    else delete next.hide
    change(next)
    filterCol = null
  }
  function clearFilters() {
    const next = { ...data }
    delete next.hide
    change(next)
    filterCol = null
  }
  function setTable(style: string | null) {
    const next = { ...data }
    if (style) next.table = { style }
    else delete next.table
    change(next)
  }

  /** Downloads the sheet as an Excel or CSV file named after the page. */
  async function download(kind: "xlsx" | "csv") {
    const name = (title.replace(/[\\/:*?"<>|]/g, " ").trim() || "Sheet") + "." + kind
    const blob =
      kind === "xlsx"
        ? new Blob([(await toXlsx(data, title)) as BlobPart], { type: "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" })
        : new Blob(["\uFEFF" + toCsv(data, calc)], { type: "text/csv" })
    const url = URL.createObjectURL(blob)
    const a = document.createElement("a")
    a.href = url
    a.download = name
    document.body.appendChild(a)
    a.click()
    a.remove()
    setTimeout(() => URL.revokeObjectURL(url), 10_000)
  }

  function refocus() {
    queueMicrotask(() => {
      if (!(document.activeElement instanceof HTMLInputElement)) gridEl?.focus({ preventScroll: true })
    })
  }

  function onWindowKey(e: KeyboardEvent) {
    if (e.key === "Escape") {
      menu = null
      menuAt = null
    }
  }
</script>

<svelte:window onkeydown={onWindowKey} />

{#if !locked}
  <div class="toolbar-row">
    <!-- After a tool, the keys go back to the grid. -->
    <div class="toolbar" role="toolbar" aria-label="Sheet tools" tabindex="-1" onclick={refocus} onkeydown={() => {}}>
      <div class="group">
        <button class="icon-btn" title="Bold (Ctrl+B)" aria-pressed={!!current.b} onclick={() => style((s) => ({ ...s, b: !current.b }))}><Icon name="bold" /></button>
        <button class="icon-btn" title="Italic (Ctrl+I)" aria-pressed={!!current.i} onclick={() => style((s) => ({ ...s, i: !current.i }))}><Icon name="italic" /></button>
        <button class="icon-btn" title="Underline (Ctrl+U)" aria-pressed={!!current.u} onclick={() => style((s) => ({ ...s, u: !current.u }))}><Icon name="underline" /></button>
      </div>
      <div class="group">
        <div class="anchor">
          <button class="icon-btn" title="Text color" aria-haspopup="menu" onclick={() => (menu = menu === "color" ? null : "color")}>
            <span class="swatch-icon" style:--swatch={current.color ?? "currentColor"}><Icon name="ink" /></span>
          </button>
          {#if menu === "color"}
            <div class="menu">
              <div class="swatches">
                {#each INK_PRESETS as c (c)}
                  <button class="swatch" style:background={inkHex(c)} aria-label="Text color {c}" aria-pressed={current.color === inkHex(c).toUpperCase()} onclick={() => ((menu = null), style((s) => ({ ...s, color: inkHex(c).toUpperCase() })))}></button>
                {/each}
              </div>
              <button onclick={() => ((menu = null), style((s) => ({ ...s, color: undefined })))}>Automatic</button>
            </div>
          {/if}
        </div>
        <div class="anchor">
          <button class="icon-btn" title="Fill color" aria-haspopup="menu" onclick={() => (menu = menu === "fill" ? null : "fill")}>
            <span class="swatch-icon" style:--swatch={current.fill ?? "transparent"}><Icon name="fill" /></span>
          </button>
          {#if menu === "fill"}
            <div class="menu">
              <div class="swatches">
                {#each FILLS as c (c)}
                  <button class="swatch" style:background={c} aria-label="Fill {c}" aria-pressed={current.fill === c} onclick={() => ((menu = null), style((s) => ({ ...s, fill: c })))}></button>
                {/each}
              </div>
              <button onclick={() => ((menu = null), style((s) => ({ ...s, fill: undefined })))}>No fill</button>
            </div>
          {/if}
        </div>
        <div class="anchor">
          <button class="icon-btn" title="Borders" aria-haspopup="menu" onclick={() => (menu = menu === "borders" ? null : "borders")}><Icon name="borders" /></button>
          {#if menu === "borders"}
            <div class="menu" role="menu">
              {#each BORDERS as [b, label] (b)}
                <button role="menuitem" onclick={() => ((menu = null), change(setBorders(data, area, b)))}>{label}</button>
              {/each}
            </div>
          {/if}
        </div>
      </div>
      <div class="group">
        <div class="anchor">
          <button class="icon-btn" title="Alignment" aria-haspopup="menu" onclick={() => (menu = menu === "align" ? null : "align")}>
            <Icon name={current.align === "center" ? "alignCenter" : current.align === "right" ? "alignRight" : "alignLeft"} />
          </button>
          {#if menu === "align"}
            <div class="menu" role="menu">
              {#each [[undefined, "Automatic (numbers right)"], ["left", "Left"], ["center", "Center"], ["right", "Right"]] as [a, label] (label)}
                <button role="menuitem" class:on={current.align === a} onclick={() => ((menu = null), style((s) => ({ ...s, align: a })))}>{label}</button>
              {/each}
            </div>
          {/if}
        </div>
        <button class="icon-btn" title="Wrap text" aria-pressed={!!current.wrap} onclick={() => style((s) => ({ ...s, wrap: !current.wrap }))}><Icon name="wrap" /></button>
        <div class="anchor">
          <button class="icon-btn num" title="Number format" aria-haspopup="menu" onclick={() => (menu = menu === "format" ? null : "format")}>123</button>
          {#if menu === "format"}
            <div class="menu" role="menu">
              {#each FORMATS as [fmt, label] (label)}
                <button role="menuitem" class:on={(current.fmt ?? undefined) === fmt || (!fmt && current.fmt === "general")} onclick={() => ((menu = null), style((s) => ({ ...s, fmt, dp: undefined })))}>{label}</button>
              {/each}
              <hr />
              <button role="menuitem" onclick={() => places(1)}>More decimal places</button>
              <button role="menuitem" onclick={() => places(-1)}>Fewer decimal places</button>
            </div>
          {/if}
        </div>
      </div>
      <div class="group">
        <button class="icon-btn" title="Sum" onclick={doAutoSum}><Icon name="sum" /></button>
        <div class="anchor">
          <button class="icon-btn" title="Sort" aria-haspopup="menu" onclick={() => (menu = menu === "sort" ? null : "sort")}><Icon name="sort" /></button>
          {#if menu === "sort"}
            <div class="menu" role="menu">
              <button role="menuitem" onclick={() => ((menu = null), sort(false))}>Sort by column {colName(area.c1)}, A → Z (1 → 9)</button>
              <button role="menuitem" onclick={() => ((menu = null), sort(true))}>Sort by column {colName(area.c1)}, Z → A (9 → 1)</button>
            </div>
          {/if}
        </div>
        <button class="icon-btn" title="Filter" aria-pressed={!!data.hide} onclick={openFilter}><Icon name="filter" /></button>
        <div class="anchor">
          <button class="icon-btn" title="Table look" aria-haspopup="menu" onclick={() => (menu = menu === "table" ? null : "table")}><Icon name="table" /></button>
          {#if menu === "table"}
            <div class="menu" role="menu">
              {#each Object.entries(TABLE_STYLES) as [name, t] (name)}
                <button role="menuitem" class:on={data.table?.style === name} onclick={() => ((menu = null), setTable(name))}>
                  <span class="table-sample" style:--h={t.header} style:--b={t.band}></span>{name[0].toUpperCase() + name.slice(1)}
                </button>
              {/each}
              <button role="menuitem" disabled={!data.table} onclick={() => ((menu = null), setTable(null))}>No table look</button>
            </div>
          {/if}
        </div>
      </div>
      <div class="group">
        <div class="anchor">
          <button class="icon-btn" title="More: rows, columns and downloads" aria-haspopup="menu" onclick={() => (menu = menu === "more" ? null : "more")}><Icon name="more" /></button>
          {#if menu === "more"}
            <div class="menu right" role="menu">
              {@render rowColItems(() => (menu = null))}
              <hr />
              <button role="menuitem" onclick={() => ((menu = null), download("xlsx"))}>Download as Excel (.xlsx)</button>
              <button role="menuitem" onclick={() => ((menu = null), download("csv"))}>Download as CSV</button>
            </div>
          {/if}
        </div>
        <button class="icon-btn" title="Undo (Ctrl+Z)" disabled={!canUndo} onclick={undo}><Icon name="undo" /></button>
        <button class="icon-btn" title="Redo (Ctrl+Y)" disabled={!canRedo} onclick={redo}><Icon name="redo" /></button>
      </div>
    </div>
    {#if menu}
      <button class="scrim" aria-label="Close menu" onclick={() => (menu = null)}></button>
    {/if}
  </div>
{/if}

{#snippet rowColItems(close: () => void)}
  {@const nr = area.r2 - area.r1 + 1}
  {@const nc = area.c2 - area.c1 + 1}
  <button role="menuitem" onclick={() => (close(), copyToClipboard(true))}>Cut</button>
  <button role="menuitem" onclick={() => (close(), copyToClipboard())}>Copy</button>
  <button role="menuitem" onclick={() => (close(), pasteFromClipboard())}>Paste</button>
  <button role="menuitem" onclick={() => (close(), change(clearArea(data, area)))}>Clear</button>
  {#if nr > 1}<button role="menuitem" onclick={() => (close(), change(fillDown(data, area)))}>Fill down</button>{/if}
  {#if nc > 1}<button role="menuitem" onclick={() => (close(), change(fillRight(data, area)))}>Fill right</button>{/if}
  {@const wholeCols = whole === "cols" || (area.r1 === 0 && area.r2 >= rows - 1)}
  {@const wholeRows = whole === "rows" || (area.c1 === 0 && area.c2 >= cols - 1)}
  {@const rowsNear = hiddenRowsNear()}
  {@const colsNear = hiddenColsNear()}
  {#if !wholeCols}
    <hr />
    <button role="menuitem" onclick={() => (close(), insertRowsAt(false))}>Insert {nr > 1 ? `${nr} rows` : "row"} above</button>
    <button role="menuitem" onclick={() => (close(), insertRowsAt(true))}>Insert {nr > 1 ? `${nr} rows` : "row"} below</button>
    <button role="menuitem" onclick={() => (close(), removeRows())}>Delete {nr > 1 ? `${nr} rows` : "row"}</button>
    <button role="menuitem" onclick={() => (close(), change(hideRows(data, area.r1, area.r2)))}>Hide {nr > 1 ? `${nr} rows` : "row"}</button>
  {/if}
  {#if rowsNear}<button role="menuitem" onclick={() => (close(), change(unhideRows(data, rowsNear[0], rowsNear[1])))}>Unhide rows</button>{/if}
  {#if !wholeRows}
    <hr />
    <button role="menuitem" onclick={() => (close(), insertColsAt(false))}>Insert {nc > 1 ? `${nc} columns` : "column"} left</button>
    <button role="menuitem" onclick={() => (close(), insertColsAt(true))}>Insert {nc > 1 ? `${nc} columns` : "column"} right</button>
    <button role="menuitem" onclick={() => (close(), removeCols())}>Delete {nc > 1 ? `${nc} columns` : "column"}</button>
    <button role="menuitem" onclick={() => (close(), change(hideCols(data, area.c1, area.c2)))}>Hide {nc > 1 ? `${nc} columns` : "column"}</button>
  {/if}
  {#if colsNear}<button role="menuitem" onclick={() => (close(), change(unhideCols(data, colsNear[0], colsNear[1])))}>Unhide columns</button>{/if}
  {#if data.hidden}<button role="menuitem" onclick={() => (close(), change(unhideAll(data)))}>Show all hidden rows and columns</button>{/if}
{/snippet}

<div class="paper" style:background={paper} style:color={ink} style:--ink={ink} style:--paper={paper}>
{@render children?.()}
<div class="bar">
  <span class="name">{areaLabel(area)}</span>
  <span class="fx">fx</span>
  <input
    bind:this={barInput}
    class="formula"
    aria-label="Cell contents"
    value={barText}
    readonly={locked}
    spellcheck="false"
    autocomplete="off"
    onfocus={() => {
      if (editing === null && !locked) {
        editCell = anchor
        whole = null
        focus = anchor
        editing = editText(anchor[0], anchor[1])
        insertedRef = null
      }
    }}
    oninput={(e) => {
      editing = (e.currentTarget as HTMLInputElement).value
      insertedRef = null
    }}
    onkeydown={inputKey}
  />
  {#if stats && editing === null}
    <span class="stats">Sum {general(stats.sum)} · Avg {general(stats.average)} · {stats.count}</span>
  {/if}
</div>

<!-- svelte-ignore a11y_no_noninteractive_tabindex -->
<div
  class="grid"
  bind:this={gridEl}
  bind:clientWidth={gridWidth}
  tabindex="0"
  role="grid"
  aria-label="Sheet"
  onkeydown={gridKey}
  oncopy={onCopy}
  oncut={onCut}
  onpaste={onPaste}
  onpointerdown={pointerDown}
  onpointermove={pointerMove}
  onpointerup={pointerUp}
  onpointercancel={() => (press = null)}
  oncontextmenu={contextMenu}
  onscroll={onScroll}
>
  <table>
    <colgroup>
      <col style:width="44px" />
      {#each colList as c (c)}<col style:width="{width(c)}px" />{/each}
    </colgroup>
    <thead>
      <tr>
        <th class="corner" aria-label="Select all"></th>
        {#each colList as c (c)}
          <th
            data-col={c}
            class:on={c >= area.c1 && c <= area.c2}
            class:filtered={!!data.hide?.[colName(c)]}
            class:after-hidden={c > 0 && colHidden(data, c - 1)}
            title={c > 0 && colHidden(data, c - 1) ? "Hidden columns before this one" : undefined}
          >
            {colName(c)}{#if data.hide?.[colName(c)]}<span class="funnel">▾</span>{/if}
            <span class="resize" role="separator" aria-label="Column {colName(c)} width" onpointerdown={(e) => startWiden(e, c)}></span>
          </th>
        {/each}
      </tr>
    </thead>
    <tbody>
      {#each rowList as r (r)}
        <tr class:hidden={hidden.has(r)}>
          <th data-row={r} class:on={r >= area.r1 && r <= area.r2} class:after-hidden={r > 0 && handHidden.has(r - 1)}>{r + 1}</th>
          {#each colList as c (c)}
            {@const isEdit = editing !== null && editCell[0] === r && editCell[1] === c}
            <td
              data-r={r}
              data-c={c}
              style={cellCss(r, c)}
              class:sel={inArea(r, c) && !(area.r1 === area.r2 && area.c1 === area.c2)}
              class:anchor={anchor[0] === r && anchor[1] === c}
              class:top={inArea(r, c) && r === area.r1}
              class:bottom={inArea(r, c) && r === area.r2}
              class:left={inArea(r, c) && c === area.c1}
              class:right={inArea(r, c) && c === area.c2}
            >
              {#if isEdit}
                <input bind:this={cellInput} class="cell-input" bind:value={editing} spellcheck="false" autocomplete="off" onkeydown={inputKey} oninput={() => (insertedRef = null)} />
              {:else}
                {calc.display(r, c)}
              {/if}
            </td>
          {/each}
        </tr>
      {/each}
    </tbody>
  </table>
</div>
</div>

{#if menuAt}
  <button class="scrim" aria-label="Close menu" onclick={() => (menuAt = null)}></button>
  <div class="menu context" role="menu" style:left="{menuAt.x}px" style:top="{menuAt.y}px">
    {#if locked}
      <button role="menuitem" onclick={() => ((menuAt = null), copyToClipboard())}>Copy</button>
    {:else}
      {@render rowColItems(() => (menuAt = null))}
      <hr />
      <button role="menuitem" onclick={() => ((menuAt = null), sort(false))}>Sort A → Z</button>
      <button role="menuitem" onclick={() => ((menuAt = null), sort(true))}>Sort Z → A</button>
    {/if}
  </div>
{/if}

{#if filterCol !== null}
  <div class="dialog-scrim" role="presentation" onclick={() => (filterCol = null)}></div>
  <div class="dialog" role="dialog" aria-label="Filter column {colName(filterCol)}">
    <h2>Filter column {colName(filterCol)}</h2>
    {#if filterValues.length === 0}
      <p>Nothing in this column yet (under the first row, which stays as the header).</p>
    {:else}
      <div class="filter-actions">
        <button onclick={() => (filterHidden = [])}>Show all</button>
        <button onclick={() => (filterHidden = [...filterValues])}>Hide all</button>
      </div>
      <div class="values">
        {#each filterValues as v (v)}
          <label>
            <input
              type="checkbox"
              checked={!filterHidden.includes(v)}
              onchange={(e) => {
                const on = (e.currentTarget as HTMLInputElement).checked
                filterHidden = on ? filterHidden.filter((x) => x !== v) : [...filterHidden, v]
              }}
            />
            {v === "" ? "(empty)" : v}
          </label>
        {/each}
      </div>
    {/if}
    <div class="dialog-buttons">
      {#if data.hide}<button onclick={clearFilters}>Clear all filters</button>{/if}
      <button onclick={() => (filterCol = null)}>Cancel</button>
      <button class="primary" onclick={applyFilter}>Apply</button>
    </div>
  </div>
{/if}

<style>
  .toolbar-row {
    position: sticky;
    top: 64px;
    z-index: 15;
    display: flex;
    justify-content: center;
    margin-bottom: 14px;
  }
  .toolbar {
    display: flex;
    align-items: center;
    gap: 2px;
    padding: 5px 8px;
    border-radius: 28px;
    background: var(--surface);
    box-shadow: var(--shadow);
    flex-wrap: wrap;
    justify-content: center;
  }
  .group {
    display: flex;
    align-items: center;
    gap: 2px;
    padding: 0 4px;
  }
  .group + .group {
    border-left: 1px solid var(--divider);
  }
  .anchor {
    position: relative;
  }
  .anchor .menu {
    top: 46px;
    left: 0;
    z-index: 41;
  }
  .anchor .menu.right {
    left: auto;
    right: 0;
  }
  :global(.menu) hr {
    border: 0;
    border-top: 1px solid var(--divider);
    margin: 4px 6px;
  }
  .menu .on {
    color: var(--accent);
    font-weight: 600;
  }
  .num {
    font-weight: 800;
    font-size: 14px;
    border: 0;
  }
  .swatch-icon {
    display: grid;
    position: relative;
  }
  .swatch-icon::after {
    content: "";
    position: absolute;
    left: 4px;
    right: 4px;
    bottom: 1px;
    height: 4px;
    border-radius: 2px;
    background: var(--swatch);
    border: 1px solid var(--divider);
  }
  .table-sample {
    display: inline-block;
    width: 26px;
    height: 18px;
    border-radius: 3px;
    border: 1px solid var(--h);
    background: linear-gradient(var(--h) 0 33%, #fff 33% 66%, var(--b) 66%);
  }
  @media (max-width: 600px) {
    .toolbar {
      padding: 4px;
      border-radius: 22px;
    }
    .group {
      padding: 0 1px;
    }
    .toolbar :global(.icon-btn) {
      width: 36px;
      height: 36px;
    }
  }
  .scrim {
    position: fixed;
    inset: 0;
    z-index: 30;
    border: 0;
    background: transparent;
  }
  .context {
    position: fixed;
    z-index: 41;
    max-height: 70vh;
    overflow: auto;
  }

  .paper {
    border-radius: 24px;
    box-shadow: 0 1px 3px rgb(0 0 0 / 0.12), 0 2px 8px rgb(0 0 0 / 0.06);
    overflow: hidden;
    padding-bottom: 8px;
  }
  .paper :global(.title-row) {
    display: flex;
    align-items: center;
  }
  .paper :global(.title) {
    flex: 1;
    min-width: 0;
    padding: 10px 0;
    border: 0;
    background: transparent;
    outline: none;
    color: inherit;
    font-size: 21px;
    font-weight: 700;
  }
  .paper :global(.title::placeholder) {
    color: currentColor;
    opacity: 0.32;
  }
  .paper :global(.lock) {
    display: grid;
    opacity: 0.42;
    margin-right: 10px;
  }
  .bar {
    display: flex;
    align-items: center;
    height: 38px;
    margin: 0 10px 6px;
    border-radius: 10px;
    background: color-mix(in srgb, var(--ink) 5%, transparent);
    font-size: 15px;
  }
  .name {
    min-width: 52px;
    max-width: 96px;
    padding: 0 10px;
    font-size: 13px;
    font-weight: 500;
    opacity: 0.75;
    border-right: 1px solid color-mix(in srgb, var(--ink) 15%, transparent);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }
  .fx {
    padding: 0 8px;
    font-family: Georgia, serif;
    font-style: italic;
    font-size: 13px;
    opacity: 0.55;
  }
  .formula {
    flex: 1;
    min-width: 0;
    height: 100%;
    border: 0;
    outline: none;
    background: transparent;
    color: inherit;
    font: inherit;
  }
  .stats {
    padding: 0 10px;
    font-size: 12px;
    opacity: 0.55;
    white-space: nowrap;
  }

  .grid {
    position: relative;
    overflow: scroll;
    scrollbar-width: auto;
    scrollbar-color: color-mix(in srgb, var(--ink) 35%, transparent) transparent;
    /* Down to just above the bottom of the window. */
    height: max(320px, calc(100dvh - 300px));
    outline: none;
    font-size: 14px;
    touch-action: pan-x pan-y;
    user-select: none;
    -webkit-user-select: none;
  }
  /* Scrollbars always showing (some systems hide them until you scroll). */
  .grid::-webkit-scrollbar {
    width: 12px;
    height: 12px;
  }
  .grid::-webkit-scrollbar-thumb {
    border: 3px solid transparent;
    border-radius: 8px;
    background-clip: padding-box;
    background-color: color-mix(in srgb, var(--ink) 35%, transparent);
  }
  .grid::-webkit-scrollbar-thumb:hover {
    background-color: color-mix(in srgb, var(--ink) 55%, transparent);
  }
  .grid::-webkit-scrollbar-corner {
    background: transparent;
  }
  thead th.after-hidden {
    border-left: 3px double color-mix(in srgb, var(--ink) 55%, transparent);
  }
  tbody th.after-hidden {
    border-top: 3px double color-mix(in srgb, var(--ink) 55%, transparent);
  }
  table {
    border-collapse: separate;
    border-spacing: 0;
    table-layout: fixed;
    width: max-content;
  }
  th,
  td {
    height: 30px;
    padding: 0 6px;
    border-right: 1px solid color-mix(in srgb, var(--ink) 13%, transparent);
    border-bottom: 1px solid color-mix(in srgb, var(--ink) 13%, transparent);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: clip;
    box-sizing: border-box;
  }
  td {
    position: relative;
    cursor: cell;
  }
  thead th {
    position: sticky;
    top: 0;
    z-index: 3;
    height: 26px;
    font-size: 12px;
    font-weight: 500;
    text-align: center;
    background: color-mix(in srgb, var(--ink) 5%, var(--paper, var(--surface)));
    color: color-mix(in srgb, var(--ink) 60%, transparent);
  }
  tbody th {
    position: sticky;
    left: 0;
    z-index: 2;
    font-size: 12px;
    font-weight: 500;
    text-align: center;
    background: color-mix(in srgb, var(--ink) 5%, var(--paper, var(--surface)));
    color: color-mix(in srgb, var(--ink) 60%, transparent);
    cursor: pointer;
  }
  thead th.corner {
    left: 0;
    z-index: 4;
  }
  thead th:not(.corner) {
    cursor: pointer;
  }
  th.on,
  th.filtered {
    color: var(--accent);
    font-weight: 700;
  }
  th.on {
    background: color-mix(in srgb, var(--accent) 14%, var(--paper, var(--surface)));
  }
  .funnel {
    margin-left: 2px;
  }
  .resize {
    position: absolute;
    top: 0;
    right: -4px;
    width: 9px;
    height: 100%;
    cursor: col-resize;
    z-index: 5;
  }
  tr.hidden {
    display: none;
  }
  td.sel {
    background-image: linear-gradient(color-mix(in srgb, var(--accent) 12%, transparent), color-mix(in srgb, var(--accent) 12%, transparent));
  }
  td.top {
    box-shadow: inset 0 2px 0 var(--accent);
  }
  td.bottom {
    box-shadow: inset 0 -2px 0 var(--accent);
  }
  td.left {
    box-shadow: inset 2px 0 0 var(--accent);
  }
  td.right {
    box-shadow: inset -2px 0 0 var(--accent);
  }
  td.top.left {
    box-shadow: inset 2px 2px 0 var(--accent);
  }
  td.top.right {
    box-shadow: inset -2px 2px 0 var(--accent);
  }
  td.bottom.left {
    box-shadow: inset 2px -2px 0 var(--accent);
  }
  td.bottom.right {
    box-shadow: inset -2px -2px 0 var(--accent);
  }
  td.top.bottom {
    box-shadow: inset 0 2px 0 var(--accent), inset 0 -2px 0 var(--accent);
  }
  td.left.right {
    box-shadow: inset 2px 0 0 var(--accent), inset -2px 0 0 var(--accent);
  }
  td.top.bottom.left {
    box-shadow: inset 2px 2px 0 var(--accent), inset 0 -2px 0 var(--accent);
  }
  td.top.bottom.right {
    box-shadow: inset -2px 2px 0 var(--accent), inset 0 -2px 0 var(--accent);
  }
  td.top.left.right {
    box-shadow: inset 2px 2px 0 var(--accent), inset -2px 0 0 var(--accent);
  }
  td.bottom.left.right {
    box-shadow: inset 2px -2px 0 var(--accent), inset -2px 0 0 var(--accent);
  }
  td.top.bottom.left.right {
    box-shadow: inset 0 0 0 2px var(--accent);
  }
  .cell-input {
    position: absolute;
    inset: 0;
    z-index: 2;
    min-width: 100%;
    width: max(100%, calc(var(--len, 0) * 1ch));
    padding: 0 5px;
    border: 2px solid var(--accent);
    outline: none;
    font: inherit;
    color: var(--ink);
    background: var(--paper, var(--surface));
    box-sizing: border-box;
  }

  .dialog-scrim {
    position: fixed;
    inset: 0;
    z-index: 50;
    background: rgb(0 0 0 / 0.3);
  }
  .dialog {
    position: fixed;
    z-index: 51;
    left: 50%;
    top: 50%;
    transform: translate(-50%, -50%);
    width: min(360px, calc(100vw - 32px));
    max-height: 80vh;
    display: flex;
    flex-direction: column;
    gap: 10px;
    padding: 20px;
    border-radius: 24px;
    background: var(--surface);
    color: var(--text);
    box-shadow: 0 12px 40px rgb(0 0 0 / 0.3);
  }
  .dialog h2 {
    margin: 0;
    font-size: 20px;
    font-weight: 500;
  }
  .values {
    overflow: auto;
    display: flex;
    flex-direction: column;
    gap: 4px;
  }
  .values label {
    display: flex;
    align-items: center;
    gap: 10px;
    padding: 6px 4px;
    cursor: pointer;
  }
  .filter-actions,
  .dialog-buttons {
    display: flex;
    gap: 8px;
    justify-content: flex-end;
  }
  .filter-actions {
    justify-content: flex-start;
  }
  .dialog button {
    padding: 8px 14px;
    border: 0;
    border-radius: 18px;
    background: transparent;
    color: var(--accent);
    font-weight: 600;
    cursor: pointer;
  }
  .dialog button.primary {
    background: var(--accent);
    color: #fff;
  }
</style>
