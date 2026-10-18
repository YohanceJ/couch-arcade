#!/usr/bin/env python3
"""Builds catalog/homebrewhub.json from the Homebrew Hub source databases.

Homebrew Hub (https://hh.gbdev.io) is a community archive of homebrew that
creators publish freely. Its data lives in public GitHub repos, one folder per
entry with a game.json, the ROM and screenshots:

    gbdev/database          Game Boy / Game Boy Color
    gbadev-org/games        Game Boy Advance
    nesdev-org/homebrew-db  NES

Usage: sync_homebrewhub.py OUT.json OWNER/REPO=PATH [OWNER/REPO=PATH ...]
where PATH is a local checkout (only the game.json files are needed).

Only "game" entries with a ROM are kept: no demos, tools, music or ROM hacks.
"""
import glob
import json
import os
import re
import subprocess
import sys
import urllib.parse

PLATFORMS = {"GB": "gb", "GBC": "gbc", "GBA": "gba", "NES": "nes"}
DEFAULT_PLATFORM = {"gbdev/database": "gb", "gbadev-org/games": "gba", "nesdev-org/homebrew-db": "nes"}
ROM_EXT = {"gb": "gb", "gbc": "gbc", "cgb": "gbc", "gba": "gba", "nes": "nes"}

# Tags that describe events or style rather than genre are dropped; a few are merged.
DROP_TAG = re.compile(r"(event:|compo|jam|showdown|shortlist|open source|^retro$|pixel art|^2d$|^3d$|8-bit|"
                      r"singleplayer|single player|^homebrew$|^game ?boy|^gba$|^nes$|^gbc?$|^wip$|no ?ai|^short$|^cute$|^indie$|gb ?studio|"
                      r"^fun$|^free$|^other$|^funny$)", re.I)
MERGE_TAG = {"platformer": "Platform", "shoot 'em up": "Shooter", "shmup": "Shooter", "rpg": "RPG",
             "puzzle game": "Puzzle", "action-adventure": "Adventure", "role playing": "RPG",
             "role-playing": "RPG", "platformers": "Platform", "racing game": "Racing"}


def clean_tags(tags):
    out = []
    for t in tags or []:
        if not isinstance(t, str) or not t.strip() or DROP_TAG.search(t):
            continue
        t = MERGE_TAG.get(t.strip().lower(), t.strip())
        t = t[0].upper() + t[1:]
        if t.lower() not in (o.lower() for o in out):
            out.append(t)
    return out


def developer(d):
    if isinstance(d, str):
        return d.strip() or None
    if isinstance(d, dict):
        return d.get("name")
    if isinstance(d, list):
        names = [x.get("name") if isinstance(x, dict) else x for x in d]
        names = [n for n in names if isinstance(n, str) and n.strip()]
        return ", ".join(names[:3]) + (" & others" if len(names) > 3 else "") if names else None
    return None


def pick_rom(files, allow_zip):
    def ext(f):
        return f.get("filename", "").rsplit(".", 1)[-1].lower()
    roms = [f for f in files or [] if ext(f) in ROM_EXT]
    if not roms and allow_zip:
        roms = [f for f in files or [] if ext(f) == "zip"]
    for want in (lambda f: f.get("default") and f.get("playable"), lambda f: f.get("playable"),
                 lambda f: f.get("default"), lambda f: True):
        for f in roms:
            if want(f):
                return f["filename"]
    return None


def raw_url(repo, branch, folder, name):
    return (f"https://raw.githubusercontent.com/{repo}/{branch}/entries/"
            f"{urllib.parse.quote(folder)}/{urllib.parse.quote(name)}")


def branch_of(path):
    try:
        return subprocess.check_output(["git", "-C", path, "rev-parse", "--abbrev-ref", "HEAD"], text=True).strip()
    except Exception:  # noqa: BLE001
        return "master"


def convert(repo, branch, path):
    folder = os.path.basename(os.path.dirname(path))
    try:
        e = json.load(open(path, encoding="utf-8"))
    except Exception as ex:  # noqa: BLE001
        print(f"skip {path}: {ex}", file=sys.stderr)
        return None
    if (e.get("typetag") or "game").lower() != "game" or e.get("nsfw"):
        return None
    rom = pick_rom(e.get("files"), allow_zip=True)
    if not rom:
        return None
    platform = PLATFORMS.get((e.get("platform") or "").upper()) or DEFAULT_PLATFORM.get(repo)
    ext = rom.rsplit(".", 1)[-1].lower()
    if ext in ("gbc", "cgb"):
        platform = "gbc"
    if not platform:
        return None
    shots = [s for s in (e.get("screenshots") or []) if isinstance(s, str)]
    desc = e.get("description") if isinstance(e.get("description"), str) else None
    if desc:
        desc = re.sub(r"\s+", " ", re.sub(r"[#*_`>]|\[([^\]]*)\]\([^)]*\)", r"\1", desc)).strip()[:400] or None
    lic = e.get("license") or e.get("gameLicense")
    game = {
        "id": f"hh-{folder}",
        "title": (e.get("title") or folder).strip(),
        "platform": platform,
        "author": developer(e.get("developer")),
        "description": desc,
        "image": raw_url(repo, branch, folder, shots[0]) if shots else None,
        "tags": clean_tags(e.get("tags")),
        "license": lic if isinstance(lic, str) and lic.strip() else None,
        "source": {"type": "url", "url": raw_url(repo, branch, folder, rom), "fileName": rom},
    }
    return {k: v for k, v in game.items() if v not in (None, [], "")}


def main(out, sources):
    games, summary = [], {}
    for src in sources:
        repo, path = src.split("=", 1)
        branch = branch_of(path)
        found = [g for g in (convert(repo, branch, p) for p in sorted(glob.glob(f"{path}/entries/*/game.json"))) if g]
        summary[repo] = len(found)
        games += found
    games.sort(key=lambda g: g["title"].lower())
    per_platform = {}
    for g in games:
        per_platform[g["platform"]] = per_platform.get(g["platform"], 0) + 1
    print(f"::notice title=Kept {len(games)} games::{summary} {per_platform}")
    if len(games) < 100:
        print(f"::error title=Too few games::Only {len(games)} games - not overwriting the list")
        sys.exit(1)
    with open(out, "w", encoding="utf-8") as f:
        json.dump({"version": 1, "name": "Homebrew Hub", "source": "https://hh.gbdev.io", "games": games},
                  f, ensure_ascii=False, separators=(",", ":"))
        f.write("\n")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2:])
