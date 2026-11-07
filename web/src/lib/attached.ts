// The pictures and voice notes kept in a page's attachments (not placed on the page itself).

import type { PageElement } from "./types"

export function elementData(e: PageElement): Record<string, any> {
  if (e.data && typeof e.data === "object") return e.data as Record<string, any>
  try {
    return typeof e.data === "string" && e.data ? JSON.parse(e.data) : {}
  } catch {
    return {}
  }
}

export function attachedOf(elements: PageElement[]): PageElement[] {
  return elements.filter((e) => {
    const d = elementData(e)
    return (e.kind === "IMAGE" && d.placement === "ATTACHED") || (e.kind === "VOICE" && d.attached === true)
  })
}

/** How much room a side column of attachments takes, gap included. */
export const SIDE_ATTACHMENTS = 212
