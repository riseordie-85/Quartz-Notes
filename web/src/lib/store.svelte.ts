// Everything signed-in: the notes, notebooks and pages, kept current by live updates, and the
// functions that save changes the same way the app does (with the revision they were based on).

import { DEVICE, isConflict, pb } from "./pb"
import {
  COLLECTIONS,
  type Attachment,
  type CollectionName,
  type Collections,
  type Notebook,
  type Page,
  type PageElement,
  type QuickNote,
  type Shelf,
  type SyncedRecord,
} from "./types"
import { emptyDoc, newId } from "./richdoc"

type Lists = { [K in CollectionName]: Collections[K][] }

class Store {
  lists: Lists = $state({ shelves: [], notebooks: [], pages: [], page_elements: [], quick_notes: [], attachments: [] })
  loaded = $state(false)
  error = $state<string | null>(null)
  /** Bumps when a record changes on another device (editors use it to reload). */
  remoteVersion = $state(0)
  private unsubscribers: (() => void)[] = []

  async load() {
    this.error = null
    const results = await Promise.all(
      COLLECTIONS.map((c) => pb.collection(c).getFullList({ filter: "deleted = false", batch: 500 })),
    )
    COLLECTIONS.forEach((c, i) => ((this.lists[c] as SyncedRecord[]) = results[i] as unknown as SyncedRecord[]))
    this.loaded = true
    await this.subscribe()
  }

  private async subscribe() {
    this.stop()
    for (const c of COLLECTIONS) {
      const off = await pb.collection(c).subscribe("*", (e) => this.put(c, e.record as unknown as SyncedRecord))
      this.unsubscribers.push(off)
    }
  }

  stop() {
    this.unsubscribers.forEach((off) => off())
    this.unsubscribers = []
  }

  reset() {
    this.stop()
    for (const c of COLLECTIONS) (this.lists[c] as SyncedRecord[]) = []
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
      .filter((n) => n.shelf === shelfId)
      .sort((a, b) => a.position - b.position || a.created.localeCompare(b.created))
  }

  /** Notebooks whose shelf isn't here (shown under the first shelf). */
  get strayNotebooks(): Notebook[] {
    const shelfIds = new Set(this.lists.shelves.map((s) => s.id))
    return this.lists.notebooks.filter((n) => !shelfIds.has(n.shelf))
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
    return this.lists.quick_notes.filter((n) => !n.archived).sort((a, b) => Number(b.pinned) - Number(a.pinned) || b.createdAt - a.createdAt)
  }

  get archived(): QuickNote[] {
    return this.lists.quick_notes.filter((n) => n.archived).sort((a, b) => b.updatedAt - a.updatedAt)
  }

  note(id: string): QuickNote | undefined {
    return this.lists.quick_notes.find((n) => n.id === id)
  }

  attachmentsOf(ownerType: string, ownerUid: string): Attachment[] {
    return this.lists.attachments.filter((a) => a.ownerType === ownerType && a.ownerUid === ownerUid)
  }

  // ---- Writing ----

  /**
   * Saves changes to a record. If it changed elsewhere since we last saw it, the more recent edit
   * wins (like the app): ours is re-sent on top, unless the other edit is newer.
   */
  async save<C extends CollectionName>(c: C, id: string, changes: Partial<Collections[C]>): Promise<Collections[C] | null> {
    const list = this.lists[c] as SyncedRecord[]
    const current = list.find((r) => r.id === id)
    if (!current) return null
    const editedAt = Date.now()
    const body = { ...changes, editedAt, device: DEVICE }
    // Show the change straight away.
    const i = list.indexOf(current)
    list[i] = { ...current, ...changes } as SyncedRecord
    let baseRev = current.rev
    for (let attempt = 0; attempt < 3; attempt++) {
      try {
        const saved = await pb.collection(c).update(id, { ...body, baseRev })
        this.put(c, saved as unknown as SyncedRecord)
        return saved as unknown as Collections[C]
      } catch (e) {
        if (!isConflict(e)) {
          list[list.findIndex((r) => r.id === id)] = current
          throw e
        }
        const latest = (await pb.collection(c).getOne(id)) as unknown as SyncedRecord
        if (latest.editedAt > editedAt) {
          this.put(c, latest)
          return null
        }
        baseRev = latest.rev
      }
    }
    return null
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

  /** Adds a page after [after] (or at the end), with the look of the page before it. */
  async addPage(notebookId: string, after: Page | null): Promise<Page> {
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
