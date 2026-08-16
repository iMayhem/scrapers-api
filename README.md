# Moovie Scraper API (Kotlin)

Ktor microservice that runs **real Cloudstream plugins** (from the
[phisher repo](https://github.com/phisher98/cloudstream-extensions-phisher)) server-side on a JVM,
scrapes **direct stream links**, and serves them to your frontend over a CORS-friendly REST API.

Scrapers are compiled plugin `.jar`/`.cs3` files in `plugins/`, loaded into isolated classloaders
(`plugin-runtime` + `android-stubs` + the real `com.lagradost.cloudstream3` library).
Update every scraper in one command — no rebuild needed.

## Endpoints

| Endpoint | Description |
|---|---|
| `GET /` | Status + loaded provider count |
| `GET /api/search?query=&type=movie\|show\|anime` | Search across all providers |
| `GET /api/home?type=&catalog=` | Homepage/browse from providers with main pages |
| `GET /api/details?url=&provider=&type=` | Media details + episodes |
| `GET /api/scrape?title=&year=&season=&episode=&type=&stream=true` | Scrape **direct links** (NDJSON when `stream=true`) |
| `GET /api/providers` | List loaded providers |
| `GET /api/config` | Server config + provider list for your frontend |
| `POST /api/reload` | Reload plugin jars from disk (after update) |
| `POST /api/token` | Generate signed stream-URL token (2 min TTL, IP-bound) |
| `GET /api/proxy?token=` | Proxy a stream URL with required headers (CORS-enabled) |

`/api/scrape` returns `{ server, url, quality, type, headers, latencyMs, providerKey }` per stream —
the `url`s are direct links your frontend can feed straight to a player (optionally via `/api/proxy`).

## Quick start

```bash
# 1. Install dependencies
./gradlew :server:installDist

# 2. Pull the latest scrapers from the phisher repo (80+ providers)
./scripts/update-plugins.sh

# 3. Run
PORT=8080 ./server/build/install/server/bin/server
```

Update all scrapers at any time:

```bash
./scripts/update-plugins.sh          # downloads new versions into plugins/
curl -X POST http://localhost:8080/api/reload   # hot-reload without restart
```

## Configuration

`config.json`:

```jsonc
{
  "repoUrl": "https://raw.githubusercontent.com/phisher98/cloudstream-extensions-phisher/builds/repo.json",
  "pluginsDir": "plugins",
  "enabledProviders": [],        // empty = all; or e.g. ["StreamPlay", "TorraStream"]
  "disabledProviders": [],       // e.g. ["Jellyfin"]
  "scrapeTimeoutSeconds": 90,
  "preferHls": true
}
```

Env vars: `PORT` (default 8080), `TOKEN_SECRET` (set a strong one!), `PLUGINS_DIR`, `CONFIG_FILE`.

## Docker (VPS)

```bash
docker build -t moovie-scraper-api .
docker run -d --name scraper-api -p 8080:8080 \
  -e TOKEN_SECRET=change_me \
  -v /srv/scraper/plugins:/app/plugins \
  moovie-scraper-api
```

On the host, update scrapers with `./scripts/update-plugins.sh` against the mounted `plugins/` volume,
then `curl -X POST http://localhost:8080/api/reload`.

## How it works

- `plugin-runtime/` — isolated classloaders, dex2jar for `.cs3`, bytecode verification (from
  [cloudstream-desktop-unofficial](https://github.com/phisher98/cloudstream-desktop-unofficial))
- `android-stubs/` — fake Android APIs so Android-style plugins run on the JVM
- `library/` — the real `com.lagradost.cloudstream3` plugin API (JVM target)
- `server/` — Ktor API: `PluginManager` (load/reload), `ScrapeService` (search → load → loadLinks)