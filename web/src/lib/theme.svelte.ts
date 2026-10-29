// Light, dark or follow the computer (remembered in this browser), plus the accent and background
// colours picked in the app, which reach the web through the server.

import { argbToCss } from "./richdoc"
import type { Prefs } from "./types"

export type ThemeMode = "system" | "light" | "dark"

const KEY = "quartz-theme"

function saved(): ThemeMode {
  try {
    const v = localStorage.getItem(KEY)
    if (v === "light" || v === "dark" || v === "system") return v
  } catch {
    // Storage blocked: follow the computer.
  }
  return "system"
}

const query = typeof matchMedia !== "undefined" ? matchMedia("(prefers-color-scheme: dark)") : null

class Theme {
  mode = $state<ThemeMode>(saved())
  private systemDark = $state(query?.matches ?? false)

  constructor() {
    query?.addEventListener("change", (e) => (this.systemDark = e.matches))
  }

  /** True when the dark colours are in use. */
  get dark(): boolean {
    return this.mode === "dark" || (this.mode === "system" && this.systemDark)
  }

  set(mode: ThemeMode) {
    this.mode = mode
    try {
      localStorage.setItem(KEY, mode)
    } catch {
      // Not remembered, still applied.
    }
  }

  /** Puts the theme and the app's colours on the page. */
  apply(prefs: Prefs | null) {
    const root = document.documentElement
    root.dataset.theme = this.dark ? "dark" : "light"
    const accent = prefs?.accentArgb ? argbToCss(prefs.accentArgb) : null
    const background = prefs?.backgroundArgb ? argbToCss(prefs.backgroundArgb) : null
    if (accent) root.style.setProperty("--accent", accent)
    else root.style.removeProperty("--accent")
    // Like the app: a background colour picked there is used in light and dark.
    if (background) root.style.setProperty("--bg", background)
    else root.style.removeProperty("--bg")
  }
}

export const theme = new Theme()
