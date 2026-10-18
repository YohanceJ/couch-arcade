#!/usr/bin/env python3
"""Builds catalog/homebrewhub.json from Homebrew Hub (https://hh.gbdev.io).

Homebrew Hub is a community archive of homebrew that creators publish freely
(GB, GBC, GBA and NES). This runs in GitHub Actions on a schedule, so the
Fire TV app only has to download one ready-made list.

Only entries typed "game" with a playable ROM are included (no ROM hacks,
which would need an original game).
"""
import json
import sys
import time
import urllib.parse
import urllib.request

API = "https://hh3.gbdev.io/api"
PLATFORMS = {"GB": "gb", "GBC": "gbc", "GBA": "gba", "NES": "nes"}
ROM_EXT = {"gb", "gbc", "gba", "nes"}


def get(path, params=None):
    url = f"{API}/{path}"
    if params:
        url += "?" + urllib.parse.urlencode(params)
    for attempt in range(4):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "CouchArcade-sync"})
            with urllib.request.urlopen(req, timeout=60) as r:
                return json.load(r)
        except Exception as e:  # noqa: BLE001
            if attempt == 3:
                raise
            print(f"retry {url}: {e}", file=sys.stderr)
            time.sleep(3 * (attempt + 1))


def fetch_all():
    """Page through /all. Copes with 0- or 1-based pages and small page caps."""
    entries = {}
    first = get("all", {"page": 1, "page_elements": 100})
    print(f"::notice title=First page::keys={list(first)[:8]} results={first.get('results')} "
          f"page_total={first.get('page_total')} page_elements={first.get('page_elements')} "
          f"page_current={first.get('page_current')} entries={len(first.get('entries', []))}")
    total = first.get("results", 0)
    pages = first.get("page_total", 1)
    per_page = first.get("page_elements", 10)
    print(f"Homebrew Hub reports {total} entries, {pages} pages of {per_page}", file=sys.stderr)

    def add(resp):
        for e in resp.get("entries", []):
            if e.get("slug"):
                entries[e["slug"]] = e

    add(first)
    for page in range(2, pages + 1):
        add(get("all", {"page": page, "page_elements": 100}))
    if len(entries) < total:
        add(get("all", {"page": 0, "page_elements": 100}))
        if pages >= 1:
            add(get("all", {"page": pages + 1, "page_elements": 100}))
    print(f"Fetched {len(entries)} unique entries", file=sys.stderr)
    return list(entries.values())


def pick_rom(files):
    roms = [f for f in files or [] if f.get("filename", "").rsplit(".", 1)[-1].lower() in ROM_EXT]
    for want in (lambda f: f.get("default") and f.get("playable"),
                 lambda f: f.get("playable"),
                 lambda f: True):
        for f in roms:
            if want(f):
                return f["filename"]
    return None


def file_url(slug, name):
    return f"{API}/entry/{urllib.parse.quote(slug)}/{urllib.parse.quote(name)}"


def convert(e):
    if (e.get("typetag") or "").lower() != "game":
        return None
    platform = PLATFORMS.get((e.get("platform") or "").upper())
    if not platform:
        return None
    rom = pick_rom(e.get("files"))
    if not rom:
        return None
    # A .gb file that's tagged GBC is really a GBC-enhanced game; the same emulator plays both.
    slug = e["slug"]
    shots = e.get("screenshots") or []
    tags = [t for t in (e.get("tags") or []) if isinstance(t, str)]
    desc = e.get("description")
    if not isinstance(desc, str) or not desc.strip():
        desc = None
    game = {
        "id": f"hh-{slug}",
        "title": (e.get("title") or slug).strip(),
        "platform": platform,
        "author": (e.get("developer") if isinstance(e.get("developer"), str) else None),
        "description": desc.strip()[:400] if desc else None,
        "image": file_url(slug, shots[0]) if shots else None,
        "tags": tags,
        "license": e.get("license") if isinstance(e.get("license"), str) else None,
        "source": {"type": "url", "url": file_url(slug, rom), "fileName": rom},
    }
    return {k: v for k, v in game.items() if v not in (None, [], "")}


def main(out):
    games = [g for g in (convert(e) for e in fetch_all()) if g]
    games.sort(key=lambda g: g["title"].lower())
    by_platform = {}
    for g in games:
        by_platform[g["platform"]] = by_platform.get(g["platform"], 0) + 1
    print(f"::notice title=Kept {len(games)} games::{by_platform}")
    if len(games) < 50:
        print(f"::error title=Too few games::Only {len(games)} games - not overwriting the list")
        sys.exit(1)
    with open(out, "w", encoding="utf-8") as f:
        json.dump({"version": 1, "name": "Homebrew Hub", "games": games}, f, ensure_ascii=False, indent=1)
        f.write("\n")


if __name__ == "__main__":
    try:
        main(sys.argv[1])
    except SystemExit:
        raise
    except Exception as e:  # noqa: BLE001
        print(f"::error title=Sync failed::{type(e).__name__}: {e}")
        raise
