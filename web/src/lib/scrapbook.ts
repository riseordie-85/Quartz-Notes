// Geometry for drawing scrapbook items the way the app does (docs/scrapbook-rendering.md §3).
// Positions and sizes are fractions of the page width W; line widths scale with W / 360.

export type Rect = { left: number; top: number; right: number; bottom: number }

const LINE_WIDTHS = [1.5, 2.5, 4, 6, 9]
const BRUSH_WIDTHS = [2, 3.5, 6, 10, 16]

export const lineWidth = (level: number, W: number) => (LINE_WIDTHS[clampInt(level - 1, 0, 4)] * W) / 360
export const brushWidth = (level: number, W: number) => (BRUSH_WIDTHS[clampInt(level - 1, 0, 4)] * W) / 360

function clampInt(v: number, lo: number, hi: number) {
  return Math.max(lo, Math.min(hi, Math.round(Number(v) || 0)))
}

/** 0 means "the page's ink"; anything else is a signed ARGB number. */
export function inkOr(argb: number | null | undefined, ink: string): string {
  if (!argb) return ink
  return argbCss(argb)
}

export function argbCss(argb: number): string {
  const v = argb >>> 0
  const a = (v >>> 24) & 0xff
  return `rgb(${(v >>> 16) & 0xff} ${(v >>> 8) & 0xff} ${v & 0xff} / ${(a / 255).toFixed(3)})`
}

export function argbAlpha(argb: number): number {
  return ((argb >>> 0) >>> 24) / 255
}

/** Relative luminance like Compose's Color.luminance(). */
export function luminance(argb: number): number {
  const v = argb >>> 0
  const lin = (c: number) => {
    const s = c / 255
    return s <= 0.04045 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4)
  }
  return 0.2126 * lin((v >>> 16) & 0xff) + 0.7152 * lin((v >>> 8) & 0xff) + 0.0722 * lin(v & 0xff)
}

export function contentColorFor(argb: number): string {
  return luminance(argb) > 0.45 ? "#111114" : "#F2F2F5"
}

// ---- Shapes ----

export function shapePath(shape: string, w: number, h: number): string {
  switch (shape) {
    case "RECTANGLE":
      return `M0 0H${w}V${h}H0Z`
    case "ROUNDED": {
      const r = Math.min(w, h) * 0.18
      return `M${r} 0H${w - r}A${r} ${r} 0 0 1 ${w} ${r}V${h - r}A${r} ${r} 0 0 1 ${w - r} ${h}H${r}A${r} ${r} 0 0 1 0 ${h - r}V${r}A${r} ${r} 0 0 1 ${r} 0Z`
    }
    case "TRIANGLE":
      return `M${w / 2} 0L${w} ${h}L0 ${h}Z`
    case "DIAMOND":
      return `M${w / 2} 0L${w} ${h / 2}L${w / 2} ${h}L0 ${h / 2}Z`
    case "STAR": {
      let d = ""
      for (let i = 0; i < 10; i++) {
        const r = i % 2 === 0 ? 0.5 : 0.21
        const a = ((-90 + 36 * i) * Math.PI) / 180
        const x = w / 2 + w * r * Math.cos(a)
        const y = h / 2 + h * r * Math.sin(a) * 1.05
        d += `${i === 0 ? "M" : "L"}${x.toFixed(2)} ${y.toFixed(2)}`
      }
      return d + "Z"
    }
    default: {
      // ELLIPSE
      const rx = w / 2
      const ry = h / 2
      return `M0 ${ry}A${rx} ${ry} 0 1 0 ${w} ${ry}A${rx} ${ry} 0 1 0 0 ${ry}Z`
    }
  }
}

// ---- Arrows, connectors, strokes ----

export function arrowHead(fromX: number, fromY: number, tipX: number, tipY: number, w: number): string {
  const angle = Math.atan2(tipY - fromY, tipX - fromX)
  const len = Math.max(w * 4, 5)
  const spread = (26 * Math.PI) / 180
  const p1x = tipX - len * Math.cos(angle - spread)
  const p1y = tipY - len * Math.sin(angle - spread)
  const p2x = tipX - len * Math.cos(angle + spread)
  const p2y = tipY - len * Math.sin(angle + spread)
  return `M${tipX} ${tipY}L${p1x} ${p1y}L${p2x} ${p2y}Z`
}

export function connectorPoints(a: Rect, b: Rect, route: string): [number, number][] {
  const acx = (a.left + a.right) / 2
  const acy = (a.top + a.bottom) / 2
  const bcx = (b.left + b.right) / 2
  const bcy = (b.top + b.bottom) / 2
  const dx = bcx - acx
  const dy = bcy - acy
  const vertical = Math.abs(dy) >= Math.abs(dx) * 0.6
  let start: [number, number]
  let end: [number, number]
  if (vertical) {
    start = dy >= 0 ? [acx, a.bottom] : [acx, a.top]
    end = dy >= 0 ? [bcx, b.top] : [bcx, b.bottom]
  } else {
    start = dx >= 0 ? [a.right, acy] : [a.left, acy]
    end = dx >= 0 ? [b.left, bcy] : [b.right, bcy]
  }
  if (route !== "ELBOW") return [start, end]
  if (vertical) {
    const midY = (start[1] + end[1]) / 2
    return [start, [start[0], midY], [end[0], midY], end]
  }
  const midX = (start[0] + end[0]) / 2
  return [start, [midX, start[1]], [midX, end[1]], end]
}

export function connectorPath(points: [number, number][], route: string): string {
  const [s, e] = [points[0], points[points.length - 1]]
  if (route === "CURVE") {
    if (Math.abs(e[0] - s[0]) > Math.abs(e[1] - s[1])) {
      const mx = (s[0] + e[0]) / 2
      return `M${s[0]} ${s[1]}C${mx} ${s[1]} ${mx} ${e[1]} ${e[0]} ${e[1]}`
    }
    const my = (s[1] + e[1]) / 2
    return `M${s[0]} ${s[1]}C${s[0]} ${my} ${e[0]} ${my} ${e[0]} ${e[1]}`
  }
  return points.map((p, i) => `${i ? "L" : "M"}${p[0]} ${p[1]}`).join("")
}

/** A brush stroke smoothed through the midpoints, like the app. Points are page-width units. */
export function strokePath(points: number[], W: number): string {
  const pts: [number, number][] = []
  for (let i = 0; i + 1 < points.length; i += 2) pts.push([points[i] * W, points[i + 1] * W])
  if (pts.length === 0) return ""
  let d = `M${pts[0][0]} ${pts[0][1]}`
  if (pts.length === 1) return d + `L${pts[0][0] + 0.1} ${pts[0][1]}`
  for (let i = 1; i < pts.length; i++) {
    const [px, py] = pts[i - 1]
    const [cx, cy] = pts[i]
    d += `Q${px} ${py} ${(px + cx) / 2} ${(py + cy) / 2}`
  }
  const last = pts[pts.length - 1]
  return d + `L${last[0]} ${last[1]}`
}

// ---- Pictures ----

export const FRAME_INSETS: Record<string, { side: number; top: number; bottom: number }> = {
  NONE: { side: 0, top: 0, bottom: 0 },
  ROUNDED: { side: 0, top: 0, bottom: 0 },
  WHITE: { side: 0.045, top: 0.045, bottom: 0.045 },
  BLACK: { side: 0.025, top: 0.025, bottom: 0.025 },
  GOLD: { side: 0.05, top: 0.05, bottom: 0.05 },
  POLAROID: { side: 0.055, top: 0.055, bottom: 0.24 },
}

export function heightFactor(frame: string, aspect: number): number {
  const f = FRAME_INSETS[frame] ?? FRAME_INSETS.WHITE
  return f.top + (1 - 2 * f.side) * aspect + f.bottom
}

/** The axis-aligned box of a rectangle rotated about its center. */
export function rotatedBounds(left: number, top: number, w: number, h: number, degrees: number): Rect {
  if (!degrees) return { left, top, right: left + w, bottom: top + h }
  const a = (degrees * Math.PI) / 180
  const cos = Math.abs(Math.cos(a))
  const sin = Math.abs(Math.sin(a))
  const bw = w * cos + h * sin
  const bh = w * sin + h * cos
  const cx = left + w / 2
  const cy = top + h / 2
  return { left: cx - bw / 2, top: cy - bh / 2, right: cx + bw / 2, bottom: cy + bh / 2 }
}

export function formatDuration(ms: number): string {
  const s = Math.floor((ms || 0) / 1000)
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`
}
