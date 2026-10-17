# Couch Arcade

A simple, controller-friendly game launcher for Fire TV. Browse a catalogue,
press **Play**, and the game downloads on demand and opens in RetroArch.
Downloaded games are cleared automatically when space runs low; RetroArch keeps
your saves separately, so progress is never lost.

Two sources of games:

- **Free homebrew** from `catalog/catalog.json` — games their creators publish freely.
- **Your own library** on a Raspberry Pi running [RomM](https://romm.app) (optional).

## Install on Fire TV

1. Install **RetroArch** with Downloader from `https://buildbot.libretro.com/stable/`
   (latest version → android → `RetroArch_aarch64.apk`).
2. In RetroArch: **Online Updater → Core Downloader** and add the emulators you want.
3. Install Couch Arcade with Downloader:
   `https://github.com/YohanceJ/couch-arcade/releases/latest/download/couch-arcade.apk`
4. Open Couch Arcade and allow storage access. Pair your controller in Fire TV settings.

## Connect your Pi (optional)

Run `pi/setup-romm.sh` on the Pi, create a client API token in RomM, then enter the
RomM address and token in Couch Arcade → Settings.

## How it fits together

| Part | What it does |
|---|---|
| `app/` | Kotlin + Compose for TV app: catalogue grid, downloader/cache, RetroArch launcher |
| `catalog/catalog.json` | Homebrew list. Entries point at GitHub releases or direct links |
| `tools/check_catalog.py` | CI check that every catalogue entry still resolves to a file |
| `.github/workflows/build.yml` | Builds the APK on every push and publishes it as the latest release |

Games are stored in `/sdcard/CouchArcade/games/` on the Fire TV.

### Adding a homebrew game

Add an entry to `catalog/catalog.json`:

```json
{
  "id": "my-game",
  "title": "My Game",
  "platform": "nes",
  "author": "Creator",
  "description": "One or two lines.",
  "source": { "type": "github", "repo": "owner/repo", "asset": "(?i)\\.nes$" }
}
```

or `"source": { "type": "url", "url": "https://…/game.zip" }`. Only add games their
creators distribute freely.
