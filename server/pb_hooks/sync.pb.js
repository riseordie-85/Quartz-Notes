/// <reference path="../pb_data/types.d.ts" />

// Sync rules for the Quartz Notes collections (requests from the app and web app; the admin
// dashboard is left alone):
//
// - a new record belongs to whoever created it and starts at rev 1;
// - an update must say which revision it was based on (`baseRev` in the request body). If the
//   record has changed since, the update is refused with 409, and the device fetches the current
//   version to decide what to do. Otherwise rev goes up by one;
// - records can't change owner;
// - media files and appearance settings belong to whoever made them.

onRecordCreateRequest(
  (e) => {
    if (!e.hasSuperuserAuth()) {
      e.record.set("owner", e.auth.id)
      e.record.set("rev", 1)
    }
    e.next()
  },
  "shelves",
  "notebooks",
  "pages",
  "page_elements",
  "quick_notes",
  "attachments",
)

onRecordUpdateRequest(
  (e) => {
    if (!e.hasSuperuserAuth()) {
      const original = e.record.original()
      const current = original.getInt("rev")
      const body = e.requestInfo().body
      const base = body["baseRev"]
      if (base === undefined || base === null || Number(base) !== current) {
        throw new ApiError(409, "This record changed on another device.")
      }
      e.record.set("owner", original.getString("owner"))
      e.record.set("rev", current + 1)
    }
    e.next()
  },
  "shelves",
  "notebooks",
  "pages",
  "page_elements",
  "quick_notes",
  "attachments",
)

onRecordCreateRequest(
  (e) => {
    if (!e.hasSuperuserAuth()) e.record.set("owner", e.auth.id)
    e.next()
  },
  "media",
  "prefs",
)

// Appearance settings keep their owner.
onRecordUpdateRequest((e) => {
  if (!e.hasSuperuserAuth()) e.record.set("owner", e.record.original().getString("owner"))
  e.next()
}, "prefs")
