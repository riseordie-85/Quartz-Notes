# Quartz Notes sync server

PocketBase with the Quartz Notes schema (`pb_migrations`), sync rules (`pb_hooks`) and, later, the
web app (`pb_public`). GitHub builds it into a Docker image, `ghcr.io/riseordie-85/quartz-sync`,
whenever `server/` changes on main. The NUC runs that image; Tailscale makes it reachable from your
devices and nowhere else.

## First-time setup on the NUC

### 1. Let the NUC download the image

The image is private on GitHub to start with. Pick one:

- **Make it public** (it only holds the schema and rules, no notes): on GitHub, your profile →
  Packages → `quartz-sync` → Package settings → Change visibility → Public.
- **Or sign the NUC in to GitHub's registry** with a token that can read packages (GitHub →
  Settings → Developer settings → Personal access tokens → classic, scope `read:packages`):

  ```sh
  echo YOUR_TOKEN | docker login ghcr.io -u riseordie-85 --password-stdin
  ```

### 2. Start it in Dockge

```sh
sudo mkdir -p /mnt/storage/quartz-sync/pb_data
```

In Dockge: **+ Compose** → name it `quartz-sync` → paste [`compose.yaml`](compose.yaml) → **Deploy**.
It listens only on the NUC itself (`127.0.0.1:8090`).

### 3. Make the admin account

```sh
docker exec quartz-sync /pb/pocketbase superuser upsert you@example.com 'a-long-password' --dir=/pb/pb_data
```

This is the server's dashboard login, separate from your notes account.

### 4. Put it on your tailnet

```sh
sudo tailscale serve --bg --https=8443 http://127.0.0.1:8090
tailscale serve status
```

The server is now at **https://rise-or-die.tail096730.ts.net:8443** from any device on your
tailnet. (If Tailscale says HTTPS isn't enabled: Tailscale admin console → DNS → enable MagicDNS and
HTTPS Certificates.)

### 5. Make your notes account

Open **https://rise-or-die.tail096730.ts.net:8443/_/**, sign in with the admin account, then
**Collections → users → New record**: your email and a password (twice) → **Create**. Nobody can
sign up on their own.

### 6. Sign in from the app

Quartz Notes → Settings → Sync → **Set up sync**:

- Server address: `rise-or-die.tail096730.ts.net:8443`
- Email and password: the notes account from step 5

Do the same on each device. The first sync merges whatever each device already has.

## Backups

Turn on PocketBase's own backups and sync those, rather than the live database file:

1. Dashboard → **Settings → Backups** → enable scheduled backups (for example daily at 3 AM,
   keep 7). They're written to `/mnt/storage/quartz-sync/pb_data/backups`.
2. Add `/mnt/storage/quartz-sync/pb_data/backups` to Syncthing, alongside Nodebook's backup.

## Updating

When the server changes, GitHub publishes a new image. In Dockge open `quartz-sync` → **Update**
(or `docker compose pull && docker compose up -d` in `/opt/stacks/quartz-sync`). Schema changes run
by themselves on start.

## Checking the rules

`tests/smoke_test.py` checks the schema and sync rules against a running server. CI runs it on
every change to `server/`:

```sh
python3 server/tests/smoke_test.py http://127.0.0.1:8090 admin@test.local adminpass123
```
