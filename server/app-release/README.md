# App builds served by the sync server

The release workflow (.github/workflows/release.yml) puts the app build for each published
server image here before building it: `quartz-notes-<version>.apk` and `version.json`. The
server serves them at `/app/`, and the app checks `/app/version.json` to offer updates
(Settings → App updates, and a card on the home screen).

Nothing here is committed besides this note.
