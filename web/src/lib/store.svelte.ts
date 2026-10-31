// Everything signed-in: the notes, notebooks and pages, kept current by live updates, and the
// functions that save changes the same way the app does (with the revision they were based on).

import { DEVICE, isConflict, pb } from "./pb"
import {
  COLLECTIONS,
  type MediaFile,
  type Attachment,
  type CollectionName,
  type Collections,
  type Notebook,
  type Page,
  type PageElement,
  type Prefs,
  type QuickNote,
  type Shelf,
  type SyncedRecord,
} from "./types"
import { compactDoc, decodeDoc, emptyDoc, newId, plainText, registerFont } from "./richdoc"
import type { StarterNote, StarterPage } from "./starters"

function isTemplatesNotebook(n: Notebook): boolean {
  return n.kind === "TEMPLATES"
}

/** True when two versions of a note or page say the same thing. */
export function sameText(a: QuickNote | Page, b: QuickNote | Page): boolean {
  return (
    a.title === b.title &&
    a.plainText === b.plainText &&
    JSON.stringify(compactDoc(decodeDoc(a.content))) === JSON.stringify(compactDoc(decodeDoc(b.content)))
  )
}

type Lists = { [K in CollectionName]: Collections[K][] }

class Store {
  lists: Lists = $state({ shelves: [], notebooks: [], pages: [], page_elements: [], quick_notes: [], attachments: [] })
  loaded = $state(false)
  error = $state<string | null>(null)
  /** Bumps when a record changes on another device (editors use it to reload). */
  remoteVersion = $state(0)
  /** Accent and background colours picked in the app (null until loaded or if never set). */
  prefs = $state<Prefs | null>(null)
  /** Stored files by app path ("media/abc.jpg", "fonts/Lora.ttf"). */
  media: Record<string, MediaFile> = $state({})
  private unsubscribers: (() => void)[] = []

  async load() {
    this.error = null
    const results = await Promise.all(
      COLLECTIONS.map((c) => pb.collection(c).getFullList({ filter: "deleted = false", batch: 500 })),
    )
    COLLECTIONS.forEach((c, i) => ((this.lists[c] as SyncedRecord[]) = results[i] as unknown as SyncedRecord[]))
    this.prefs = ((await pb.collection("prefs").getFullList().catch(() => []))[0] as unknown as Prefs | undefined) ?? null
    const files = (await pb.collection("media").getFullList({ batch: 500 })) as unknown as MediaFile[]
    for (const f of files) this.media[f.path] = f
    await this.loadFonts(files)
    this.loaded = true
    await this.subscribe()
  }

  private async subscribe() {
    this.stop()
    this.unsubscribers.push(
      await pb.collection("media").subscribe("*", (e) => {
        const f = e.record as unknown as MediaFile
        this.media[f.path] = f
        if (f.path.startsWith("fonts/")) this.loadFonts([f])
      }),
    )
    this.unsubscribers.push(
      await pb.collection("prefs").subscribe("*", (e) => (this.prefs = e.record as unknown as Prefs)).catch(() => () => {}),
    )
    for (const c of COLLECTIONS) {
      const off = await pb.collection(c).subscribe("*", (e) => this.put(c, e.record as unknown as SyncedRecord))
      this.unsubscribers.push(off)
    }
  }

  /** Makes imported fonts available to the page (they're used as "file:<name>"). */
  private async loadFonts(files: MediaFile[]) {
    await Promise.all(
      files
        .filter((f) => f.path.startsWith("fonts/"))
        .map(async (f) => {
          const name = f.path.slice("fonts/".length)
          const family = `Quartz ${name.replace(/\.[^.]+$/, "")}`
          try {
            const face = new FontFace(family, `url("${this.fileUrl(f)}")`)
            document.fonts.add(await face.load())
            registerFont(`file:${name}`, family)
          } catch {
            // A font the browser can't read just isn't used.
          }
        }),
    )
  }

  fileUrl(f: MediaFile): string {
    return pb.files.getURL(f as never, f.file)
  }

  /** A URL for a stored file, or null if it hasn't been synced. */
  mediaUrl(path: string | null | undefined): string | null {
    if (!path) return null
    const f = this.media[path]
    return f ? this.fileUrl(f) : null
  }

  /** Uploads a file as [path] (e.g. "media/<id>.jpg"). */
  async uploadFile(path: string, blob: Blob, filename: string): Promise<MediaFile> {
    const form = new FormData()
    form.append("path", path)
    form.append("size", String(blob.size))
    form.append("file", blob, filename)
    const f = (await pb.collection("media").create(form)) as unknown as MediaFile
    this.media[f.path] = f
    return f
  }

  stop() {
    this.unsubscribers.forEach((off) => off())
    this.unsubscribers = []
  }

  reset() {
    this.stop()
    for (const c of COLLECTIONS) (this.lists[c] as SyncedRecord[]) = []
    this.media = {}
    this.prefs = null
    this.loaded = false
  }

  /** Puts a record from the server into the lists (newer revisions only). */
  put(c: CollectionName, rec: SyncedRecord) {
    const list = this.lists[c] as SyncedRecord[]
    const i = list.findIndex((r) => r.id === rec.id)
    if (i >= 0 && list[i].rev > rec.rev) return
    if (rec.deleted) {
      if (i >= 0) list.splice(i, 1)
    } else if (i >= 0) list[i] = rec
    else list.push(rec)
    if (rec.device !== DEVICE) this.remoteVersion++
  }

  // ---- Reading ----

  get shelves(): Shelf[] {
    return [...this.lists.shelves].sort((a, b) => a.position - b.position || a.created.localeCompare(b.created))
  }

  notebooksOn(shelfId: string): Notebook[] {
    return this.lists.notebooks
      .filter((n) => n.shelf === shelfId && !isTemplatesNotebook(n))
      .sort((a, b) => a.position - b.position || a.created.localeCompare(b.created))
  }

  /** Notebooks whose shelf isn't here (shown under the first shelf). */
  get strayNotebooks(): Notebook[] {
    const shelfIds = new Set(this.lists.shelves.map((s) => s.id))
    return this.lists.notebooks.filter((n) => !shelfIds.has(n.shelf) && !isTemplatesNotebook(n))
  }

  notebook(id: string): Notebook | undefined {
    return this.lists.notebooks.find((n) => n.id === id)
  }

  pagesOf(notebookId: string): Page[] {
    return this.lists.pages
      .filter((p) => p.notebook === notebookId)
      .sort((a, b) => a.position - b.position || a.created.localeCompare(b.created))
  }

  page(id: string): Page | undefined {
    return this.lists.pages.find((p) => p.id === id)
  }

  elementsOf(pageId: string): PageElement[] {
    return this.lists.page_elements.filter((e) => e.page === pageId).sort((a, b) => a.z - b.z)
  }

  /** Quick notes: pinned first, then newest. */
  get notes(): QuickNote[] {
    return this.lists.quick_notes.filter((n) => !n.archived && !n.templateName).sort((a, b) => Number(b.pinned) - Number(a.pinned) || b.createdAt - a.createdAt)
  }

  get archived(): QuickNote[] {
    return this.lists.quick_notes.filter((n) => n.archived && !n.templateName).sort((a, b) => b.updatedAt - a.updatedAt)
  }

  /** Saved page templates (pages in the hidden templates notebook), by name. */
  get pageTemplates(): Page[] {
    const holders = new Set(this.lists.notebooks.filter(isTemplatesNotebook).map((n) => n.id))
    return this.lists.pages.filter((p) => holders.has(p.notebook) && p.templateName).sort((a, b) => a.templateName.localeCompare(b.templateName))
  }

  /** Saved quick-note templates, by name. */
  get noteTemplates(): QuickNote[] {
    return this.lists.quick_notes.filter((n) => n.templateName).sort((a, b) => a.templateName.localeCompare(b.templateName))
  }

  /** True for pages and notes that are templates (left out of search). */
  isTemplate(r: Page | QuickNote): boolean {
    if ("notebook" in r) {
      const nb = this.notebook(r.notebook)
      return !!nb && isTemplatesNotebook(nb)
    }
    return !!r.templateName
  }

  note(id: string): QuickNote | undefined {
    return this.lists.quick_notes.find((n) => n.id === id)
  }

  attachmentsOf(ownerType: string, ownerUid: string): Attachment[] {
    return this.lists.attachments.filter((a) => a.ownerType === ownerType && a.ownerUid === ownerUid)
  }

  // ---- Writing ----

  /**
   * Saves changes to a record. [baseRev] is the revision the changes were made on (editors pass the
   * one they last showed; otherwise the newest one here). If the record changed elsewhere since,
   * the more recent edit wins, like the app, and for notes and pages the other version is kept as
   * a "conflict copy" so nothing is lost.
   */
  async save<C extends CollectionName>(
    c: C,
    id: string,
    changes: Partial<Collections[C]>,
    baseRev?: number,
  ): Promise<Collections[C] | null> {
    const list = this.lists[c] as SyncedRecord[]
    const current = list.find((r) => r.id === id)
    if (!current) return null
    const editedAt = Date.now()
    const body = { ...changes, editedAt, device: DEVICE }
    const mine = { ...current, ...changes, device: DEVICE } as SyncedRecord
    // Show the change straight away.
    const i = list.indexOf(current)
    list[i] = mine
    let base = baseRev ?? current.rev
    for (let attempt = 0; attempt < 3; attempt++) {
      try {
        const saved = await pb.collection(c).update(id, { ...body, baseRev: base })
        this.put(c, saved as unknown as SyncedRecord)
        return saved as unknown as Collections[C]
      } catch (e) {
        if (!isConflict(e)) {
          list[list.findIndex((r) => r.id === id)] = current
          throw e
        }
        const latest = (await pb.collection(c).getOne(id)) as unknown as SyncedRecord
        if (latest.editedAt > editedAt) {
          // The other edit is newer: it stays, and ours becomes the copy.
          this.put(c, latest)
          await this.keepCopy(c, mine, latest)
          return null
        }
        if (attempt === 0) await this.keepCopy(c, latest, mine)
        base = latest.rev
      }
    }
    return null
  }

  /** Keeps [loser] as a new note or page, if its text differs from [winner]'s. */
  private async keepCopy(c: CollectionName, loser: SyncedRecord, winner: SyncedRecord) {
    if (c !== "quick_notes" && c !== "pages") return
    const a = loser as QuickNote | Page
    const b = winner as QuickNote | Page
    if (a.deleted || b.deleted || sameText(a, b)) return
    const title = a.title.trim() ? `${a.title} (conflict copy)` : "Conflict copy"
    const now = Date.now()
    const fields: Record<string, unknown> = { ...a }
    for (const k of ["id", "owner", "rev", "created", "updated", "collectionId", "collectionName", "expand", "editedAt", "device"])
      delete fields[k]
    if (c === "quick_notes") {
      await this.create("quick_notes", { ...(fields as Partial<QuickNote>), title, pinned: false, createdAt: now, updatedAt: now })
    } else {
      const page = a as Page
      const position = Math.max(-1, ...this.pagesOf(page.notebook).map((p) => p.position)) + 1
      await this.create("pages", { ...(fields as Partial<Page>), title, locked: false, position, createdAt: now, updatedAt: now })
    }
  }

  async create<C extends CollectionName>(c: C, fields: Partial<Collections[C]>): Promise<Collections[C]> {
    const now = Date.now()
    const rec = (await pb.collection(c).create({
      id: newId(),
      deleted: false,
      createdAt: now,
      ...fields,
      editedAt: now,
      device: DEVICE,
    })) as unknown as SyncedRecord
    this.put(c, rec)
    return rec as unknown as Collections[C]
  }

  /** Deleting marks the record, so every device hears about it. */
  async remove(c: CollectionName, id: string) {
    await this.save(c, id, { deleted: true } as never)
    const list = this.lists[c] as SyncedRecord[]
    const i = list.findIndex((r) => r.id === id)
    if (i >= 0) list.splice(i, 1)
  }

  // ---- Notes and pages ----

  /** A new quick note made from a template (saved or starter), with a saved one's pictures. */
  async newNoteFrom(source: QuickNote | StarterNote): Promise<QuickNote> {
    const saved = "id" in source ? source : null
    const doc = decodeDoc(source.content)
    const now = Date.now()
    const note = await this.create("quick_notes", {
      title: source.title,
      content: compactDoc(doc),
      plainText: saved ? saved.plainText : plainText(doc),
      colorKey: source.colorKey,
      pinned: false,
      archived: false,
      templateName: "",
      updatedAt: now,
    })
    if (saved) {
      for (const a of this.attachmentsOf("NOTE", saved.id)) {
        await this.create("attachments", {
          ownerType: "NOTE", ownerUid: note.id, kind: a.kind, path: a.path, mimeType: a.mimeType,
          durationMs: a.durationMs, width: a.width, height: a.height, createdAt: now,
        })
      }
    }
    return note
  }

  async newNote(): Promise<QuickNote> {
    const now = Date.now()
    return this.create("quick_notes", {
      title: "",
      content: emptyDoc(),
      plainText: "",
      colorKey: "default",
      pinned: false,
      archived: false,
      updatedAt: now,
    })
  }

  async deleteNote(id: string) {
    for (const a of this.attachmentsOf("NOTE", id)) await this.remove("attachments", a.id)
    await this.remove("quick_notes", id)
  }

  /** Stores a picture file and attaches it to a quick note. */
  async addNotePicture(noteId: string, pic: { blob: Blob; ext: string; width: number; height: number }) {
    const name = `${newId()}.${pic.ext}`
    await this.uploadFile(`media/${name}`, pic.blob, name)
    await this.create("attachments", {
      ownerType: "NOTE",
      ownerUid: noteId,
      kind: "IMAGE",
      path: `media/${name}`,
      mimeType: "",
      durationMs: null,
      width: pic.width,
      height: pic.height,
    })
    await this.save("quick_notes", noteId, { updatedAt: Date.now() })
  }

  private stagger = 0

  /**
   * Stores a picture file and puts it on a notebook page, the way the app does: a framed picture
   * near the top, slightly tilted (or in the attachments strip, if the notebook keeps them there).
   */
  async addPagePicture(page: Page, pic: { blob: Blob; ext: string; width: number; height: number }) {
    const name = `${newId()}.${pic.ext}`
    await this.uploadFile(`media/${name}`, pic.blob, name)
    const atBottom = this.notebook(page.notebook)?.attachAtBottom ?? false
    const aspect = pic.width > 0 ? pic.height / pic.width : 1
    const width = 0.6
    const step = this.stagger++ % 5
    const z = Math.max(0, ...this.elementsOf(page.id).map((e) => e.z)) + 1
    await this.create("page_elements", {
      page: page.id,
      kind: "IMAGE",
      x: (1 - width) / 2 + (step - 2) * 0.03,
      y: 0.18 + step * 0.06,
      width,
      height: width * aspect,
      rotation: atBottom ? 0 : [-3, 2, -1.5, 3, 0][step],
      z,
      data: { path: `media/${name}`, aspect, frame: "WHITE", tape: false, shadow: true, caption: "", placement: atBottom ? "ATTACHED" : "FLOAT" },
    })
    await this.save("pages", page.id, { updatedAt: Date.now() })
  }

  /** Adds a page after [after] (or at the end), with the look of the page before it. */
  // ---- Notebooks ----

  /** A new notebook at the end of [shelfId], with one blank page (like the app). */
  async createNotebook(shelfId: string, fields: { title: string; colorArgb: number; icon: string }): Promise<Notebook> {
    const now = Date.now()
    const nb = await this.create("notebooks", {
      shelf: shelfId,
      title: fields.title.trim(),
      colorArgb: fields.colorArgb,
      texture: "GLOSS",
      icon: fields.icon,
      position: this.notebooksOn(shelfId).length,
      createdAt: now,
      updatedAt: now,
      twoPage: true,
      showIndex: false,
      sectionTabs: true,
      attachAtBottom: false,
    })
    await this.addPage(nb.id, null)
    return nb
  }

  /** Changes a notebook's title, colour and icon, and moves it if [shelfId] is another shelf. */
  async editNotebook(nb: Notebook, fields: { title: string; colorArgb: number; icon: string }, shelfId: string) {
    await this.save("notebooks", nb.id, { title: fields.title.trim(), colorArgb: fields.colorArgb, icon: fields.icon, updatedAt: Date.now() })
    if (shelfId !== nb.shelf) {
      const moved = this.notebook(nb.id)
      if (moved) await this.moveNotebook(moved, shelfId, this.notebooksOn(shelfId).length)
    }
  }

  // ---- Pages ----

  /** Moves a page to [index] in its notebook, renumbering the others. */
  async movePage(page: Page, index: number) {
    const pages = this.pagesOf(page.notebook).filter((p) => p.id !== page.id)
    pages.splice(Math.max(0, Math.min(pages.length, index)), 0, page)
    await Promise.all(pages.map((p, i) => (p.position !== i ? this.save("pages", p.id, { position: i }) : null)))
    await this.save("notebooks", page.notebook, { updatedAt: Date.now() })
  }

  /**
   * Moves a notebook to [index] among the notebooks on [shelfId] (another shelf moves it there),
   * renumbering the shelf so every device shows the same order.
   */
  async moveNotebook(nb: Notebook, shelfId: string, index: number) {
    const from = nb.shelf
    const target = this.notebooksOn(shelfId).filter((n) => n.id !== nb.id)
    const at = Math.max(0, Math.min(target.length, index))
    target.splice(at, 0, nb)
    const saves: Promise<unknown>[] = []
    target.forEach((n, i) => {
      if (n.id === nb.id && (n.position !== i || n.shelf !== shelfId)) saves.push(this.save("notebooks", n.id, { shelf: shelfId, position: i }))
      else if (n.id !== nb.id && n.position !== i) saves.push(this.save("notebooks", n.id, { position: i }))
    })
    if (from !== shelfId) {
      this.notebooksOn(from)
        .filter((n) => n.id !== nb.id)
        .forEach((n, i) => n.position !== i && saves.push(this.save("notebooks", n.id, { position: i })))
    }
    await Promise.all(saves)
  }

  /** Deletes a notebook with its pages and everything on them, on every device. */
  async deleteNotebook(nb: Notebook) {
    const pages = this.pagesOf(nb.id)
    await Promise.all(
      pages.flatMap((p) => [
        ...this.elementsOf(p.id).map((e) => this.remove("page_elements", e.id)),
        ...this.attachmentsOf("PAGE", p.id).map((a) => this.remove("attachments", a.id)),
      ]),
    )
    await Promise.all(pages.map((p) => this.remove("pages", p.id)))
    await this.remove("notebooks", nb.id)
  }

  /** Adds a page made from a template (saved or starter) after [after]. */
  async addPageFrom(notebookId: string, after: Page | null, source: Page | StarterPage): Promise<Page> {
    const saved = "id" in source ? source : null
    const fields = saved ?? { ...source, content: source.content }
    const page = await this.addPage(notebookId, after, {
      title: fields.title ?? "",
      pageType: fields.pageType ?? "LINES",
      backgroundArgb: fields.backgroundArgb ?? null,
      texture: fields.texture ?? "NONE",
      layoutMode: fields.layoutMode ?? "INLINE",
      content: fields.content,
      plainText: saved ? saved.plainText : plainText(decodeDoc(fields.content)),
      isSection: saved?.isSection ?? false,
      sectionIcon: saved?.sectionIcon ?? "",
      sectionImage: saved?.sectionImage ?? "",
      backgroundImage: saved?.backgroundImage ?? "",
    })
    if (saved) {
      // Everything placed on the template comes too; connectors point at the copies.
      const ids = new Map<string, string>()
      const elements = this.elementsOf(saved.id)
      for (const e of elements) ids.set(e.id, newId())
      for (const e of elements) {
        let data = e.data as Record<string, unknown>
        if (e.kind === "CONNECTOR" && data) data = { ...data, from: ids.get(String(data.from)) ?? data.from, to: ids.get(String(data.to)) ?? data.to }
        const { id: _i, owner: _o, rev: _r, created: _c, updated: _u, collectionName: _n, editedAt: _e, device: _d, ...rest } = e as PageElement & Record<string, unknown>
        await this.create("page_elements", { ...(rest as Partial<PageElement>), id: ids.get(e.id), page: page.id, data } as Partial<PageElement>)
      }
    }
    return page
  }

  async addPage(notebookId: string, after: Page | null, from?: Partial<Page>): Promise<Page> {
    const pages = this.pagesOf(notebookId)
    const index = after ? pages.findIndex((p) => p.id === after.id) : pages.length - 1
    const template = pages.slice(0, index + 1).reverse().find((p) => !p.isSection) ?? pages.find((p) => !p.isSection)
    const at = index + 1
    // Make room: later pages move down one.
    for (const p of pages.slice(at)) await this.save("pages", p.id, { position: p.position + 1 })
    const now = Date.now()
    return this.create("pages", {
      notebook: notebookId,
      title: "",
      position: at,
      pageType: template?.pageType ?? "LINES",
      backgroundArgb: template?.backgroundArgb ?? null,
      texture: template?.texture ?? "NONE",
      layoutMode: "INLINE",
      content: emptyDoc(),
      plainText: "",
      updatedAt: now,
      locked: false,
      spread: false,
      isSection: false,
      sectionIcon: "",
      sectionImage: "",
      templateName: "",
      backgroundImage: template?.backgroundImage ?? "",
      ...from,
    })
  }

  /** Deletes a page and what's on it. A notebook keeps at least one page. */
  async deletePage(page: Page): Promise<boolean> {
    const pages = this.pagesOf(page.notebook)
    if (pages.length <= 1) return false
    for (const e of this.elementsOf(page.id)) await this.remove("page_elements", e.id)
    for (const a of this.attachmentsOf("PAGE", page.id)) await this.remove("attachments", a.id)
    await this.remove("pages", page.id)
    const rest = pages.filter((p) => p.id !== page.id)
    for (let i = 0; i < rest.length; i++) if (rest[i].position !== i) await this.save("pages", rest[i].id, { position: i })
    return true
  }
}

export const store = new Store()
