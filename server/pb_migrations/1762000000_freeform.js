/// <reference path="../pb_data/types.d.ts" />

// Freeform pages and background picture fitting:
// - pages.canvasWidth / canvasHeight: a Freeform page's fixed size in page units (dp / points /
//   CSS px); empty on text pages. sizeName: which size it is ("A4", "COVER" …).
// - pages.backgroundFit: how the background picture is laid out ("FIT", "FILL", "MANUAL:x:y:s" …).
// - notebooks.pageLayout / pageSize: the type ("INLINE" or "SCRAPBOOK") and size of new pages.

migrate(
  (app) => {
    const add = (collectionName, field) => {
      const c = app.findCollectionByNameOrId(collectionName)
      c.fields.add(new Field(field))
      app.save(c)
    }
    add("pages", { name: "canvasWidth", type: "number" })
    add("pages", { name: "canvasHeight", type: "number" })
    add("pages", { name: "sizeName", type: "text", max: 32 })
    add("pages", { name: "backgroundFit", type: "text", max: 64 })
    add("notebooks", { name: "pageLayout", type: "text", max: 32 })
    add("notebooks", { name: "pageSize", type: "text", max: 64 })
  },
  (app) => {
    const drop = (collectionName, name) => {
      const c = app.findCollectionByNameOrId(collectionName)
      c.fields.removeByName(name)
      app.save(c)
    }
    for (const f of ["canvasWidth", "canvasHeight", "sizeName", "backgroundFit"]) drop("pages", f)
    drop("notebooks", "pageLayout")
    drop("notebooks", "pageSize")
  },
)
