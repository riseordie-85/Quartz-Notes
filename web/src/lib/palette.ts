// Colors from the app (ui/theme/Palette.kt).

export interface NoteColor {
  key: string
  label: string
  light: string
  dark: string
  dot: string
}

export const NOTE_COLORS: NoteColor[] = [
  { key: "default", label: "Default", light: "#FFFFFF", dark: "#1C1C21", dot: "transparent" },
  { key: "yellow", label: "Yellow", light: "#FFF4B8", dark: "#4A4220", dot: "#F2C500" },
  { key: "orange", label: "Orange", light: "#FFE0C2", dark: "#4D3320", dot: "#F08A1E" },
  { key: "red", label: "Red", light: "#FFD3D3", dark: "#4D2424", dot: "#E53935" },
  { key: "pink", label: "Pink", light: "#FFD6E8", dark: "#4A2234", dot: "#D6004C" },
  { key: "purple", label: "Purple", light: "#E6DAFF", dark: "#34284D", dot: "#7C4DFF" },
  { key: "blue", label: "Blue", light: "#D5E6FF", dark: "#213350", dot: "#1E6FE8" },
  { key: "teal", label: "Teal", light: "#CDF1EC", dark: "#1C3F3A", dot: "#00A896" },
  { key: "green", label: "Green", light: "#DDF3CF", dark: "#2A4022", dot: "#43A047" },
  { key: "gray", label: "Gray", light: "#E7E7EB", dark: "#34343A", dot: "#8A8A92" },
]

export function noteColor(key: string | null | undefined): NoteColor {
  return NOTE_COLORS.find((c) => c.key === key) ?? NOTE_COLORS[0]
}

/** Ink colors offered for text (ARGB hex, the way the app stores them). */
export const INK_PRESETS = ["FF111114", "FFFFFFFF", "FFD6004C", "FF1E6FE8", "FF2E9E5B", "FFE68A1E", "FF7C4DFF", "FF8A8A92"]
export const HIGHLIGHT_PRESETS = ["FFFFE14D", "FFB7F59A", "FFA8D8FF", "FFFFB3D1", "FFFFC48A", "FFD7B8FF"]

export const FONTS = [
  { id: null, label: "Default" },
  { id: "sans", label: "Sans" },
  { id: "serif", label: "Serif" },
  { id: "mono", label: "Mono" },
  { id: "cursive", label: "Script" },
]

/** Dark or light text for a background color. */
export function inkOn(css: string): string {
  const m = css.match(/^#([0-9a-f]{6})/i)
  if (!m) return "#111114"
  const n = parseInt(m[1], 16)
  const lum = (0.2126 * ((n >> 16) & 255) + 0.7152 * ((n >> 8) & 255) + 0.0722 * (n & 255)) / 255
  return lum > 0.55 ? "#111114" : "#F2F2F5"
}
