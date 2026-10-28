import PocketBase, { ClientResponseError } from "pocketbase"

/** The sync server: the same address the web app is served from. */
export const pb = new PocketBase(location.origin)
pb.autoCancellation(false)

/** This browser's id, sent with every change so live updates of our own edits can be told apart. */
export const DEVICE: string = (() => {
  const key = "quartz-device"
  try {
    const existing = localStorage.getItem(key)
    if (existing) return existing
    const id = `web-${crypto.randomUUID()}`
    localStorage.setItem(key, id)
    return id
  } catch {
    return `web-${crypto.randomUUID()}`
  }
})()

export function errorMessage(e: unknown): string {
  if (e instanceof ClientResponseError) {
    if (e.status === 0) return "Can't reach the sync server"
    if (e.status === 400 && e.url.includes("auth-with-password")) return "That email and password didn't work"
    return e.message || `The server answered ${e.status}`
  }
  return e instanceof Error ? e.message : String(e)
}

export function isConflict(e: unknown): boolean {
  return e instanceof ClientResponseError && e.status === 409
}
