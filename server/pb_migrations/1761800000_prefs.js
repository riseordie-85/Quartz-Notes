/// <reference path="../pb_data/types.d.ts" />

// Appearance settings shared by every device and the web app: one record per account with the
// accent and background colours picked in the app. The newest change (by editedAt) wins.

migrate(
  (app) => {
    const users = app.findCollectionByNameOrId("users")
    const collection = new Collection({
      type: "base",
      name: "prefs",
      listRule: "owner = @request.auth.id",
      viewRule: "owner = @request.auth.id",
      createRule: "@request.auth.id != '' && @request.auth.collectionName = 'users'",
      updateRule: "owner = @request.auth.id",
      deleteRule: null,
      fields: [
        { name: "owner", type: "relation", collectionId: users.id, maxSelect: 1, required: true, cascadeDelete: true },
        { name: "accentArgb", type: "number", onlyInt: true },
        { name: "backgroundArgb", type: "number", onlyInt: true },
        { name: "editedAt", type: "number", onlyInt: true },
        { name: "device", type: "text", max: 64 },
        { name: "created", type: "autodate", onCreate: true, onUpdate: false },
        { name: "updated", type: "autodate", onCreate: true, onUpdate: true },
      ],
      indexes: ["CREATE UNIQUE INDEX idx_prefs_owner ON prefs (owner)"],
    })
    app.save(collection)
  },
  (app) => {
    app.delete(app.findCollectionByNameOrId("prefs"))
  },
)
