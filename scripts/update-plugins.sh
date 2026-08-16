#!/usr/bin/env bash
# update-plugins.sh — pull the latest Cloudstream plugin jars from the phisher repo
# (or any CS3 repo.json) into ./plugins, so the scraper API always runs fresh scrapers.
#
# Usage:
#   ./scripts/update-plugins.sh                 # uses repoUrl from config.json (default: phisher builds)
#   ./scripts/update-plugins.sh <repo.json-url> # override repository
#   ./scripts/update-plugins.sh --list          # list available plugins without downloading
#
# Run this on your VPS whenever you want to update all scrapers. No restart needed
# if the API is running — call POST /api/reload (or restart the container).

set -euo pipefail

cd "$(dirname "$0")/.."

DEFAULT_REPO="https://raw.githubusercontent.com/phisher98/cloudstream-extensions-phisher/builds/repo.json"
PLUGINS_DIR="plugins"
MANIFEST="$PLUGINS_DIR/.manifest.json"

if [[ "${1:-}" == "--list" ]]; then
  REPO_URL="${2:-$DEFAULT_REPO}"
  LIST_ONLY=1
else
  REPO_URL="${1:-$DEFAULT_REPO}"
  LIST_ONLY=0
fi

if [[ -f config.json ]]; then
  REPO_URL="$(python3 -c 'import json,sys; print(json.load(open("config.json")).get("repoUrl", sys.argv[1]))' "$REPO_URL")"
fi

mkdir -p "$PLUGINS_DIR"

echo "==> Repository: $REPO_URL"

python3 - "$REPO_URL" "$PLUGINS_DIR" "$LIST_ONLY" <<'PY'
import hashlib, json, os, sys, urllib.request

repo_url, plugins_dir, list_only = sys.argv[1], sys.argv[2], bool(int(sys.argv[3]))

def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": "moovie-updater"})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read()

def sha256_of(data):
    return "sha256-" + hashlib.sha256(data).hexdigest()

repo = json.loads(fetch(repo_url))
lists = repo.get("pluginLists", [])
print(f"==> Plugin lists: {len(lists)}")

manifest = {}
if os.path.exists(os.path.join(plugins_dir, ".manifest.json")):
    manifest = json.load(open(os.path.join(plugins_dir, ".manifest.json")))

downloaded, skipped, failed = 0, 0, 0

for list_url in lists:
    plugins = json.loads(fetch(list_url))
    print(f"==> {len(plugins)} plugins in {list_url}")
    for p in sorted(plugins, key=lambda x: x["name"].lower()):
        name = p["name"]
        version = p.get("version", 0)
        jar_url = p.get("jarUrl")
        jar_hash = p.get("jarHash")
        cs3_url = p.get("url")
        url = jar_url or cs3_url
        expected = jar_hash

        if not url:
            print(f"    ! {name}: no download url")
            failed += 1
            continue

        filename = f"{name}.jar" if jar_url else f"{name}.cs3"
        dest = os.path.join(plugins_dir, filename)

        if list_only:
            print(f"    {name} v{version}  {os.path.basename(url)}")
            continue

        if manifest.get(name) == version and os.path.exists(dest):
            skipped += 1
            continue

        try:
            data = fetch(url)
            if expected and sha256_of(data) != expected:
                print(f"    ! {name}: hash mismatch, keeping old file")
                failed += 1
                continue
            with open(dest, "wb") as f:
                f.write(data)
            manifest[name] = version
            print(f"    ✓ {name} v{version}  ({len(data)//1024} KiB)")
            downloaded += 1
        except Exception as e:
            print(f"    ! {name}: {e}")
            failed += 1

if not list_only:
    with open(os.path.join(plugins_dir, ".manifest.json"), "w") as f:
        json.dump(manifest, f, indent=2)
    print(f"==> done: {downloaded} downloaded, {skipped} up-to-date, {failed} failed")
    if downloaded:
        print("==> Restart the API or call POST /api/reload to load the new scrapers.")
PY