<p align="center">
  <img src="docs/images/zaomeng_logo.png" alt="Zaomeng" width="120">
</p>

# Zaomeng

> “Some characters were not fully written away. They were only never truly awakened.”

Distill Chinese novel characters into reusable persona bundles, extract relationship graphs, and let characters speak again with their own personality, stance, bonds, and memory.

[![License: AGPL-3.0-only](https://img.shields.io/badge/License-AGPL--3.0--only-8A2BE2.svg)](LICENSE) · [Website](https://dz3197578117-lab.github.io/zaomeng/) · [中文](README.md)

> This repository continues from upstream Zaomeng 2.0 (current version: **2.1.2**). See "What's different in this build" below.

## What's different in this build

Two things were the focus: **it runs without any network**, and **Chinese model providers work out of the box**.

| Change | Detail |
| --- | --- |
| New · on-device inference | The APK embeds a llama.cpp runtime and the settings screen gains a "On-device models" page that imports GGUF files. Chapter summaries, single-turn continuation, relation/timeline extraction, and the distillation pipeline can all run locally — no API key required, and your models and prompts never leave the device |
| Five more provider presets | SenseNova (SenseTime), SiliconFlow, Moonshot Kimi, Zhipu GLM, MiniMax — 16 presets in total |
| Models fetched from the API | The model settings screen can pull the live list via `GET {baseUrl}/models`; it falls back to the built-in presets on failure |
| Ollama base URL fixed | `http://127.0.0.1:11434` → adds the missing `/v1`, which otherwise produced a wrong `/chat/completions` path |
| On-device 500 fixed | Chat messages are normalized (at most one `system`, always first), fixing `System message must be at the beginning.` raised by Jinja templates embedded in open-weight GGUF files |
| Relation graph edges | The relations screen used to draw nodes only. It now draws the links between them: width and opacity scale with relation strength (green = high trust, red = high hostility, purple = high affection), and the relation type is labelled at each midpoint. Clicking a node opens that persona for review |
| Persona quality overview | The run detail screen gains an overview card: character count, items needing review, pending repairs, missing fields, and high-severity issues, with a score and grade per character |
| Main characters extracted from the text | The book-import and re-distillation flows can detect the main characters from the source text and fill in the list for you |
| Pick the starting chapter | When creating a dialogue session you can choose which chapter the story starts from; the chapter list is served by a chapter index service |

A file-by-file manifest (33 added / 63 modified / 0 deleted against upstream, with a reproduction method) is in [`docs/changes-from-upstream.md`](docs/changes-from-upstream.md).

## Project layout

| Path | Description | Status |
| --- | --- | --- |
| [`kmp/`](kmp/README.md) | Compose Multiplatform client: shared UI across Android / Desktop / iOS with an embedded Ktor + Room backend | ✅ actively maintained |
| `zaomeng-skill/` | OpenClaw / ClawHub skill bundle (CLI / agent environments) | ✅ kept |
| `src/`, `src/web/` | Early Python backend and Web UI | ⛔ **no longer maintained**; kept as a behavioral reference and test baseline |

## Install

### Clients

Prebuilt Android builds are on [Releases](https://github.com/dz3197578117-lab/zaomeng/releases/latest) (`arm64-v8a`, currently a 2.1.2 debug build with the llama.cpp runtime bundled). Build instructions for Desktop / iOS live in [`kmp/README.md`](kmp/README.md).

> ⚠️ Upgrading from the legacy Android build (1.5.0 and earlier) to **2.0.0** is a **breaking change**: local data is incompatible and is not migrated automatically. **Export your book bundles (`.zaomeng-run.zip`) as backups in the old version before upgrading.**

### Skill bundle

```bash
# Local skills directory (the zaomeng-skill/ bundle ships with this repository)
python scripts/install_skill.py --skills-dir <your-skills-root>

# Or into a project: installs to <project-root>/skills/zaomeng-skill
python scripts/install_skill.py --project-root <project-root>

# ClawHub (installs by name from the registry)
npx clawhub@latest install zaomeng-skill
```

### Web UI (legacy, no longer maintained, still installable)

```bash
# One-click install (Linux / macOS / WSL2 / Termux)
curl -fsSL https://raw.githubusercontent.com/dz3197578117-lab/zaomeng/main/scripts/install.sh | bash
source ~/.bashrc
zaomeng
```

Common commands after install: `zaomeng web --reload` (starts the Web UI at `http://127.0.0.1:8000`), `zaomeng uninstall`, `zaomeng update`. Manual setup: `pip install -r requirements.runtime.txt` then `python scripts/run_webui.py --reload`. Dependencies: `requirements.txt` (full dev/test), `requirements.termux.txt` (Termux), including `httpx2` for web tests; EPUB parsing is optional.

## Screenshots

| Mobile | Desktop |
| --- | --- |
| <img src="docs/images/mobile.jpg" width="240" alt="Mobile"> | <img src="docs/images/desktop.png" width="480" alt="Desktop"> |

## Community

- QQ group: **1090225658**

<p>
  <img src="docs/assets/qq-group.png" alt="Zaomeng QQ group QR code, group 1090225658" width="360">
</p>

- Book bundles / issue reports: [Issues](https://github.com/dz3197578117-lab/zaomeng/issues)

## License

[AGPL-3.0](LICENSE)
