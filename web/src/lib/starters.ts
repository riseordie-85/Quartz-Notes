// Ready-made templates, shared with the app (shared/templates/starters.json).

import data from "../../../shared/templates/starters.json"

export interface StarterPage {
  title: string
  pageType: string
  backgroundArgb: number | null
  texture: string
  layoutMode: string
  content: unknown
}

export interface StarterNote {
  title: string
  colorKey: string
  content: unknown
}

export const STARTER_PAGES: { key: string; name: string; page: StarterPage }[] = data.pages.map((p) => ({
  key: p.key,
  name: p.name,
  page: {
    title: p.page.title,
    pageType: p.page.pageType,
    backgroundArgb: (p.page as { backgroundArgb?: number }).backgroundArgb ?? null,
    texture: (p.page as { texture?: string }).texture ?? "NONE",
    layoutMode: p.page.layoutMode,
    content: p.page.content,
  },
}))

export const STARTER_NOTES: { key: string; name: string; note: StarterNote }[] = data.notes.map((n) => ({
  key: n.key,
  name: n.name,
  note: { title: n.note.title, colorKey: n.note.colorKey, content: n.note.content },
}))
