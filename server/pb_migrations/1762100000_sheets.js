/// <reference path="../pb_data/types.d.ts" />

// Sheet (spreadsheet) pages: pages.sheet holds the grid as JSON (see docs/sheet-format.md);
// empty on other pages. Such pages have layoutMode "SHEET".

migrate(
  (app) => {
    const c = app.findCollectionByNameOrId("pages")
    c.fields.add(new Field({ name: "sheet", type: "json", maxSize: 5 * 1024 * 1024 }))
    app.save(c)
  },
  (app) => {
    const c = app.findCollectionByNameOrId("pages")
    c.fields.removeByName("sheet")
    app.save(c)
  },
)
