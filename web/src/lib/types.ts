// Records as the sync server stores them (see server/pb_migrations and docs/sync-plan.md).

export interface SyncedRecord {
  id: string
  collectionName?: string
  owner: string
  deleted: boolean
  rev: number
  device: string
  editedAt: number
  created: string
  updated: string
}

export interface Shelf extends SyncedRecord {
  name: string
  position: number
  createdAt: number
}

export interface Notebook extends SyncedRecord {
  shelf: string
  title: string
  colorArgb: number
  texture: string
  icon: string
  position: number
  createdAt: number
  updatedAt: number
  twoPage: boolean
  showIndex: boolean
  sectionTabs: boolean
  attachAtBottom: boolean
  /** "TEMPLATES" for the hidden notebook of page templates ("" or "NOTEBOOK" otherwise). */
  kind: string
}

export interface Page extends SyncedRecord {
  notebook: string
  title: string
  position: number
  pageType: string
  backgroundArgb: number | null
  texture: string
  layoutMode: string
  content: unknown
  plainText: string
  createdAt: number
  updatedAt: number
  locked: boolean
  spread: boolean
  isSection: boolean
  sectionIcon: string
  sectionImage: string
  /** Set on saved page templates. */
  templateName: string
  /** Media path of a picture used as the page's paper. */
  backgroundImage: string
}

export interface PageElement extends SyncedRecord {
  page: string
  kind: string
  x: number
  y: number
  width: number
  height: number
  rotation: number
  z: number
  data: unknown
  createdAt: number
}

export interface QuickNote extends SyncedRecord {
  title: string
  content: unknown
  plainText: string
  colorKey: string
  pinned: boolean
  createdAt: number
  updatedAt: number
  archived: boolean
  /** Set on saved quick-note templates (hidden from the lists). */
  templateName: string
}

export interface Attachment extends SyncedRecord {
  ownerType: string
  ownerUid: string
  kind: string
  path: string
  mimeType: string
  durationMs: number | null
  width: number | null
  height: number | null
  createdAt: number
}

export type CollectionName = "shelves" | "notebooks" | "pages" | "page_elements" | "quick_notes" | "attachments"

export interface Collections {
  shelves: Shelf
  notebooks: Notebook
  pages: Page
  page_elements: PageElement
  quick_notes: QuickNote
  attachments: Attachment
}

export const COLLECTIONS: CollectionName[] = ["shelves", "notebooks", "pages", "page_elements", "quick_notes", "attachments"]

/** A stored file: picture, voice note or font, found by its app path. */
export interface MediaFile {
  id: string
  collectionId: string
  collectionName: string
  owner: string
  path: string
  file: string
  size: number
  created: string
}

/** Accent and background colours shared by the app (ARGB ints; background 0 = theme default). */
export interface Prefs {
  id: string
  accentArgb: number
  backgroundArgb: number
  editedAt: number
}
