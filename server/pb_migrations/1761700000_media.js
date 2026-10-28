/// <reference path="../pb_data/types.d.ts" />

// Pictures, voice notes and imported fonts. Each record is one file, found by the path the app uses
// for it ("media/abc.jpg", "fonts/Lora.ttf"). Files never change once uploaded (a changed picture
// gets a new path), so records can only be created.

migrate(
  (app) => {
    const users = app.findCollectionByNameOrId("users")
    const collection = new Collection({
      type: "base",
      name: "media",
      listRule: "owner = @request.auth.id",
      viewRule: "owner = @request.auth.id",
      createRule: "@request.auth.id != '' && @request.auth.collectionName = 'users'",
      updateRule: null,
      deleteRule: null,
      fields: [
        { name: "owner", type: "relation", collectionId: users.id, maxSelect: 1, required: true, cascadeDelete: true },
        { name: "path", type: "text", required: true, max: 512, pattern: "^(media|fonts)/[A-Za-z0-9 ._-]+$" },
        { name: "sha256", type: "text", max: 64 },
        { name: "size", type: "number", onlyInt: true },
        { name: "file", type: "file", required: true, maxSelect: 1, maxSize: 100 * 1024 * 1024 },
        { name: "created", type: "autodate", onCreate: true, onUpdate: false },
        { name: "updated", type: "autodate", onCreate: true, onUpdate: true },
      ],
      indexes: ["CREATE UNIQUE INDEX idx_media_owner_path ON media (owner, path)"],
    })
    app.save(collection)
  },
  (app) => {
    app.delete(app.findCollectionByNameOrId("media"))
  },
)
