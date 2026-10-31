/// <reference path="../pb_data/types.d.ts" />

// Templates and page background pictures:
// - notebooks.kind: "NOTEBOOK", or "TEMPLATES" for the hidden notebook holding saved page templates;
// - pages.templateName: set on pages in that notebook (the name the template is listed under);
// - pages.backgroundImage: media path of a picture used as the page's paper;
// - quick_notes.templateName: set on saved quick-note templates (hidden from the notes list).

migrate(
  (app) => {
    const add = (collectionName, field) => {
      const c = app.findCollectionByNameOrId(collectionName)
      c.fields.add(new Field(field))
      app.save(c)
    }
    add("notebooks", { name: "kind", type: "text", max: 32 })
    add("pages", { name: "templateName", type: "text", max: 200 })
    add("pages", { name: "backgroundImage", type: "text", max: 512 })
    add("quick_notes", { name: "templateName", type: "text", max: 200 })
  },
  (app) => {
    const drop = (collectionName, name) => {
      const c = app.findCollectionByNameOrId(collectionName)
      c.fields.removeByName(name)
      app.save(c)
    }
    drop("notebooks", "kind")
    drop("pages", "templateName")
    drop("pages", "backgroundImage")
    drop("quick_notes", "templateName")
  },
)
