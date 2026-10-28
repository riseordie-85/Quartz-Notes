// The picture viewer: which pictures it's showing and which one is in front.

import { store } from "./store.svelte"
import { decodeDoc } from "./richdoc"
import type { Page, PageElement } from "./types"

export interface Picture {
  path: string
  caption?: string
}

class Lightbox {
  pictures: { url: string; caption: string }[] = $state([])
  index = $state(0)
  get isOpen() {
    return this.pictures.length > 0
  }

  /** Opens [pictures] (those that have synced) at the one with [path]. */
  open(pictures: Picture[], path: string) {
    const shown = pictures
      .map((p) => ({ path: p.path, url: store.mediaUrl(p.path), caption: p.caption ?? "" }))
      .filter((p): p is { path: string; url: string; caption: string } => !!p.url)
    if (!shown.length) return
    this.index = Math.max(0, shown.findIndex((p) => p.path === path))
    this.pictures = shown.map(({ url, caption }) => ({ url, caption }))
  }

  close() {
    this.pictures = []
  }

  step(delta: number) {
    const n = this.pictures.length
    if (n) this.index = (this.index + delta + n) % n
  }
}

export const lightbox = new Lightbox()

function payload(e: PageElement): Record<string, any> {
  if (e.data && typeof e.data === "object") return e.data as Record<string, any>
  try {
    return typeof e.data === "string" && e.data ? JSON.parse(e.data) : {}
  } catch {
    return {}
  }
}

function inlinePictures(content: unknown): Picture[] {
  return decodeDoc(content)
    .blocks.filter((b) => b.type === "IMAGE" && b.image?.path)
    .map((b) => ({ path: b.image!.path }))
}

/** Every picture in a quick note: in the text, then the attached ones. */
export function notePictures(noteId: string): Picture[] {
  const note = store.note(noteId)
  return [
    ...(note ? inlinePictures(note.content) : []),
    ...store.attachmentsOf("NOTE", noteId).filter((a) => a.kind === "IMAGE").map((a) => ({ path: a.path })),
  ]
}

/** Every picture on a page, top to bottom: section photo, pictures in the text, placed ones, the attachments strip. */
export function pagePictures(page: Page): Picture[] {
  const out: Picture[] = []
  if (page.sectionImage) out.push({ path: page.sectionImage })
  if (page.layoutMode !== "SCRAPBOOK") out.push(...inlinePictures(page.content))
  const elements = store.elementsOf(page.id)
  const placed: { y: number; x: number; pics: Picture[] }[] = []
  const attached: Picture[] = []
  for (const e of elements) {
    const d = payload(e)
    if (e.kind === "IMAGE" && d.path) {
      const pic = { path: d.path as string, caption: (d.caption as string) || "" }
      if (d.placement === "ATTACHED") attached.push(pic)
      else placed.push({ y: e.y, x: e.x, pics: [pic] })
    } else if (e.kind === "TEXT") {
      placed.push({ y: e.y, x: e.x, pics: inlinePictures(d.doc) })
    }
  }
  placed.sort((a, b) => a.y - b.y || a.x - b.x)
  for (const p of placed) out.push(...p.pics)
  return [...out, ...attached]
}
