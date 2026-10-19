# Quartz Notes

A native Android notes app for quick notes and detailed notebooks — Kotlin + Jetpack Compose, Android 13+, built for phones, tablets and foldables.

## Building

Open the project in Android Studio and run the `app` configuration, or from a terminal:

```
./gradlew assembleDebug      # debug APK
./gradlew assembleRelease    # release APK (sign it in Android Studio: Build > Generate Signed App Bundle / APK)
```

Every push also builds on GitHub Actions (`.github/workflows/android.yml`); the debug APK is attached to each run as an artifact.

## Project layout

```
app/src/main/java/com/quartz/notes/
  data/            Room database, repository, settings (DataStore), fonts
    rich/          Rich text document model (blocks + style spans, stored as JSON)
  ui/
    home/          Main screen: shelves, notebook covers, Quick Notes (list/grid), two-pane layout
    note/          Quick note editor
    notebook/      Notebook pages (pager), page style, pages list, notebook editor sheet
    editor/        Rich text editor, toolbar, "Aa" format sheet
    search/        Global and per-notebook search
    recent/        Recently edited pages
    settings/      Theme, colors, shelves, fonts, images
    components/    Covers, textures, page rulings, color picker, shared widgets
    theme/         Colors, palettes, typography
```

## Roadmap

- **Phase 1 (done):** shelves, notebooks (colors, textures, titles, icons), page types (plain / lines / dotted / grid) with colors and textures, rich text (heading, subheading, regular, bold, italic, underline, strikeout, bullets, checkable tasks, alignment, fonts + font import, highlight with long-press color, text color), Quick Notes (list/grid, grouped by date, colors), global + notebook search, settings (theme, colors, shelves, fonts), phone + tablet/fold layouts.
- **Phase 2:** images (tape, borders, shadows, polaroid captions, full-size view, compression), voice notes, share a note as text / image / send to a notebook, backup & export (manual + automatic to a folder you choose).
- **Phase 3:** scrapbook mode — place anything anywhere, shapes with text, arrows, node connectors, brush with 5 thicknesses.
