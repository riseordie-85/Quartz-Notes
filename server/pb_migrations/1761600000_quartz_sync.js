/// <reference path="../pb_data/types.d.ts" />

// Quartz Notes sync schema. One collection per synced app table. Each record's id is the row's
// permanent id from the app (its uid), so devices can create and update records without asking
// the server for ids first. See docs/sync-plan.md.

const OWNER_RULE = "owner = @request.auth.id"
const CREATE_RULE = "@request.auth.id != '' && @request.auth.collectionName = 'users'"

const BIG_TEXT = 2000000 // characters
const BIG_JSON = 5 * 1024 * 1024 // bytes

function text(name, max) {
  return { name: name, type: "text", max: max || 1000 }
}
function int(name) {
  return { name: name, type: "number", onlyInt: true }
}
function num(name) {
  return { name: name, type: "number" }
}
function bool(name) {
  return { name: name, type: "bool" }
}
function json(name) {
  return { name: name, type: "json", maxSize: BIG_JSON }
}

/** Fields every synced collection has, ahead of its own. */
function common(usersId) {
  return [
    {
      name: "id",
      type: "text",
      system: true,
      primaryKey: true,
      required: true,
      min: 15,
      max: 36,
      pattern: "^[a-z0-9-]+$",
      autogeneratePattern: "[a-z0-9]{15}",
    },
    { name: "owner", type: "relation", collectionId: usersId, maxSelect: 1, required: true, cascadeDelete: true },
    // Deleted on a device; kept so every device hears about it.
    bool("deleted"),
    // Increased by the server on every change (see pb_hooks/sync.pb.js).
    int("rev"),
    // Which device made the last change, so it can ignore its own live updates.
    text("device", 64),
    // When the change was made, by the device's clock (ms). Decides conflicts.
    int("editedAt"),
  ]
}

function timestamps() {
  return [
    { name: "created", type: "autodate", onCreate: true, onUpdate: false },
    { name: "updated", type: "autodate", onCreate: true, onUpdate: true },
  ]
}

function synced(app, name, fields) {
  const users = app.findCollectionByNameOrId("users")
  const collection = new Collection({
    type: "base",
    name: name,
    listRule: OWNER_RULE,
    viewRule: OWNER_RULE,
    createRule: CREATE_RULE,
    updateRule: OWNER_RULE,
    // Nothing is ever removed through the API; deleting sets `deleted`.
    deleteRule: null,
    fields: common(users.id).concat(fields, timestamps()),
    indexes: [
      `CREATE INDEX idx_${name}_owner_updated ON ${name} (owner, updated)`,
    ],
  })
  app.save(collection)
}

const TABLES = ["shelves", "notebooks", "pages", "page_elements", "quick_notes", "attachments"]

migrate(
  (app) => {
    synced(app, "shelves", [text("name"), int("position"), int("createdAt")])

    synced(app, "notebooks", [
      text("shelf", 36),
      text("title"),
      int("colorArgb"),
      text("texture", 64),
      text("icon", 64),
      int("position"),
      int("createdAt"),
      int("updatedAt"),
      bool("twoPage"),
      bool("showIndex"),
      bool("sectionTabs"),
      bool("attachAtBottom"),
    ])

    synced(app, "pages", [
      text("notebook", 36),
      text("title"),
      int("position"),
      text("pageType", 64),
      // A number, or null for the theme's page color.
      json("backgroundArgb"),
      text("texture", 64),
      text("layoutMode", 64),
      json("content"),
      text("plainText", BIG_TEXT),
      int("createdAt"),
      int("updatedAt"),
      bool("locked"),
      bool("spread"),
      bool("isSection"),
      text("sectionIcon", 128),
      text("sectionImage", 512),
    ])

    synced(app, "page_elements", [
      text("page", 36),
      text("kind", 64),
      num("x"),
      num("y"),
      num("width"),
      num("height"),
      num("rotation"),
      int("z"),
      json("data"),
      int("createdAt"),
    ])

    synced(app, "quick_notes", [
      text("title"),
      json("content"),
      text("plainText", BIG_TEXT),
      text("colorKey", 64),
      bool("pinned"),
      int("createdAt"),
      int("updatedAt"),
      bool("archived"),
    ])

    synced(app, "attachments", [
      text("ownerType", 16),
      text("ownerUid", 36),
      text("kind", 16),
      text("path", 512),
      text("mimeType", 128),
      json("durationMs"),
      json("width"),
      json("height"),
      int("createdAt"),
    ])

    // Accounts are made by the server admin only; nobody can sign up. Sign-ins last 30 days and
    // are renewed by the app while it's used.
    const users = app.findCollectionByNameOrId("users")
    users.createRule = null
    users.authToken.duration = 30 * 24 * 60 * 60
    app.save(users)
  },
  (app) => {
    for (const name of TABLES.slice().reverse()) {
      try {
        app.delete(app.findCollectionByNameOrId(name))
      } catch (_) {}
    }
  },
)
