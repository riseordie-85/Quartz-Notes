// What's being dragged inside the app. Pictures dragged within the page aren't new files to add
// (the browser offers them as files, which once made a copy on every drag); an attachment
// dragged onto another page in the list moves there.

import type { PageElement } from "./types"

class Drag {
  /** A drag that started in this window (anything: text, a picture, a page in the list). */
  internal = false
  /** A page's attachment being dragged. */
  attachment = $state<PageElement | null>(null)

  constructor() {
    if (typeof window === "undefined") return
    window.addEventListener("dragstart", () => (this.internal = true), true)
    const end = () => {
      // After the drop handlers have seen it.
      setTimeout(() => {
        this.internal = false
        this.attachment = null
      })
    }
    window.addEventListener("dragend", end, true)
    window.addEventListener("drop", end, true)
  }
}

export const drag = new Drag()

export const ATTACHMENT_TYPE = "application/x-quartz-attachment"
