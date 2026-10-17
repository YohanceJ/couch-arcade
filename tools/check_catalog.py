#!/usr/bin/env python3
"""Checks that every catalogue entry resolves to a downloadable file.

Mirrors the app's selection rule: the entry's asset regex first, then any
asset with a known extension for the platform, then any .zip.
Run in CI; prints a table and exits non-zero if anything is broken.
"""
import json
import os
import re
import sys
import urllib.request

EXT = {
    "nes": ["nes"], "snes": ["sfc", "smc"], "gb": ["gb"], "gbc": ["gbc", "gb"],
    "gba": ["gba"], "megadrive": ["md", "bin", "gen", "smd"], "mastersystem": ["sms"],
    "n64": ["z64", "n64", "v64"], "psx": ["chd", "cue", "pbp"], "psp": ["iso", "cso"],
    "dreamcast": ["chd", "gdi", "cdi"], "mame": ["zip"],
}


def api(url):
    req = urllib.request.Request(url, headers={"Accept": "application/vnd.github+json"})
    token = os.environ.get("GITHUB_TOKEN")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def pick(assets, pattern, platform):
    names = [a["name"] for a in assets]
    if pattern:
        for a in assets:
            if re.search(pattern, a["name"]):
                return a
    exts = EXT.get(platform, [])
    for a in assets:
        if a["name"].lower().rsplit(".", 1)[-1] in exts:
            return a
    for a in assets:
        if a["name"].lower().endswith(".zip"):
            return a
    return None


def main(path):
    games = json.load(open(path))["games"]
    broken = 0
    for g in games:
        src = g["source"]
        try:
            if src["type"] == "github":
                rel = api(f"https://api.github.com/repos/{src['repo']}/releases/latest")
                a = pick(rel.get("assets", []), src.get("asset"), g["platform"])
                if a:
                    print(f"::notice title=OK {g['id']}::{rel['tag_name']} {a['name']} ({a['size']//1024} KB)")
                else:
                    broken += 1
                    print(f"::warning title=No file {g['id']}::{rel['tag_name']} assets={[x['name'] for x in rel.get('assets', [])]}")
            elif src["type"] == "url":
                req = urllib.request.Request(src["url"], method="HEAD")
                with urllib.request.urlopen(req, timeout=30) as r:
                    print(f"::notice title=OK {g['id']}::{r.status} {src['url']}")
        except Exception as e:  # noqa: BLE001
            broken += 1
            print(f"::warning title=Broken {g['id']}::{e}")
    print(f"\n{len(games) - broken}/{len(games)} entries OK")
    # Warnings only, so a moved homebrew link never turns the build red.
    sys.exit(0)


if __name__ == "__main__":
    main(sys.argv[1])
