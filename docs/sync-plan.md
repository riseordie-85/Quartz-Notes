# Quartz Notes sync plan

Notes sync between the phone, the tablet and a web app, through a server on the home NUC.
No Firebase and nothing exposed to the internet.

## Decisions

- **Server:** PocketBase (one container, SQLite inside), run as a Docker Compose stack on the NUC
  (`/opt/stacks/quartz-sync`, managed in Dockge). Its data folder lives on the external drive.
- **Access:** Tailscale only, through `tailscale serve` (HTTPS on the tailnet, e.g.
  `https://rise-or-die.tail096730.ts.net:8443`). Nothing is reachable from the public internet.
- **Offline first:** each device's own database stays the master copy. The app works the same
  with no connection; changes wait and sync when the server is reachable.
- **Conflicts:** the latest edit wins, and the losing version is kept as a "conflict copy", so
  nothing disappears. A change arriving for the page that's being typed on never replaces the
  typing; it becomes a conflict copy instead.
- **Web app:** served by PocketBase itself (same address, same login). Edits quick notes and
  page text with the same formatting as the app. Scrapbook items (text boxes, shapes, pictures,
  drawing) are view-only on the web to start; editing them comes later.
- **Backups:** the PocketBase data folder joins the existing Syncthing → PC → Google Drive chain.
  The app's own .zip backups keep working.

## Repo layout

```
app/       Android app
server/    docker-compose.yml, PocketBase schema migrations (pb_migrations), hooks (pb_hooks)
web/       Web app (Vite + TypeScript + TipTap), built into server/pb_public
docs/      This plan and the note format definition
shared/    Sample notes used by both the app's and the web app's tests
```

## The note format (shared contract)

The phone, the tablet and the web app must all save notes the same way. The format is written
down in `docs/note-format.md`, and a set of sample notes in `shared/fixtures/` covers every kind
of formatting. Both the Kotlin tests and the web tests load and save every sample and check that
nothing changes.

Rich text (`RichDoc`): a list of blocks. Each block has a type (heading, subheading, body,
bullet, task, image, divider), alignment, indent, checked, text, and character style spans (bold,
italic, underline, strikethrough, highlight + color, ink color, font). Picture blocks carry the
media path, aspect and width.

## Server collections

Every collection has an `owner` (the logged-in user; rules allow access only to the owner's own
records), a client-made `uid` (unique, permanent), `deleted` (tombstone), `rev` (increased on
every change, used to detect conflicts), `device` (which device made the change, so a device can
ignore its own live updates), `editedAt` (when the change was made, by the device's clock) and
PocketBase's own `updated` time, which devices use to ask for changes since their last sync.

| Collection | Fields |
|---|---|
| shelves | name, position |
| notebooks | shelf (uid), title, color, texture, icon, position, twoPage, showIndex, sectionTabs, attachAtBottom |
| pages | notebook (uid), title, position, pageType, background, texture, layoutMode, content (RichDoc JSON), plainText, locked, spread, isSection, sectionIcon, sectionImage |
| elements | page (uid), kind, x, y, width, height, rotation, z, data (JSON) |
| quick_notes | title, content (RichDoc JSON), plainText, colorKey, pinned, archived |
| attachments | ownerType, owner record (uid), kind, path, mimeType, durationMs, width, height |
| media | path (the app's relative path, e.g. `media/abc.jpg` or `fonts/x.ttf`), sha256, file |

Each record's id is the app row's uid. A server hook checks `rev` on every save: the client sends
`baseRev`, the revision it last saw; if the record changed since, the save is refused with 409 and
the client fetches the current version. The more recent edit (by `editedAt`, the device's clock)
wins. Conflict copies of the losing version come in Phase 5.

## App changes

1. **Unique IDs:** every syncable row gets a permanent `uid`. Links between records (notebook →
   shelf, page → notebook, element → page, attachment → owner) are synced by uid; local numeric
   ids stay for the app's own database.
2. **Deletions that sync:** deletes mark rows as deleted (tombstones) instead of erasing them.
   Tombstones are purged once synced and old.
3. **Change tracking:** `updatedAt`, `rev`, and a "needs upload" flag on every syncable table
   (shelves, elements and attachments don't have change times yet). One Room migration (4 → 5).
4. **Sync engine:**
   - Upload: about two seconds after edits stop, changed rows go to the server.
   - Download: everything newer than the last sync, by the server's clock.
   - Live: while the app is open, a realtime subscription pulls changes within a second or two.
   - Background: WorkManager sync every 15 minutes, on app open, and when the network returns.
5. **Media:** pictures, voice notes and imported fonts upload as files (deduplicated by hash);
   other devices download them when they're first needed.
6. **Settings → Sync:** server address, login, sync now, last synced, errors. Device-specific
   settings (like toolbar at the top) stay on each device.
7. **First sync:** if both devices already have notes, both sets merge. Nothing is replaced.

## Web app

- Login; quick notes (pinned, archived); shelves → notebooks → pages in order, with section tabs;
  search across everything.
- TipTap editor mapped to RichDoc: headings, lists, checklists, alignment, indents, bold, italic,
  underline, strikethrough, highlight, ink color, imported fonts, pictures in line, dividers.
- Live updates both ways while a tab is open.
- Scrapbook pages show every item in place (view only).
- Installable from the browser. Online-only to start; offline reading later.

## Status

- Phase 1 (app groundwork) is done: database version 5.
- Phase 2 (server and app text sync) is done: see `server/README.md` to set up the NUC.

## Phases

| Phase | Work | Needs the NUC |
|---|---|---|
| 0 | Server stack, Tailscale serve address, schema migrations, hooks, note format doc | Yes |
| 1 | App groundwork: uids, tombstones, change tracking, Room migration. No visible change | No |
| 2 | Text sync for the app (shelves, notebooks, pages, quick notes, elements), Sync settings | Tested in CI; NUC to use |
| 3 | Web app: login, browsing, search, editing quick notes and page text, live updates | Tested in CI; NUC to use |
| 4 | Pictures, voice notes and fonts on all three, including adding pictures from the web | Tested in CI; NUC to use |
| 5 | Live updates in the app, conflict copies, first-sync merge, sync status | Tested in CI; NUC to use |
| 6 | Scrapbook editing on the web (later) | — |

## Testing

- CI downloads the PocketBase binary and starts a temporary server with the repo's migrations.
- Two simulated devices run against it: edits, deletions, offline edits on both sides,
  conflicts, first-sync merges, media.
- The web app's tests round-trip every shared sample note through the editor.
- The emulator UI check keeps covering the app's screens.
