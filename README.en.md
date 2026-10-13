<p align="center">
  <img src="docs/images/zaomeng_logo.png" alt="Zaomeng" width="120">
</p>

# Zaomeng

> “Some characters were not fully written away. They were only never truly awakened.”

Distill Chinese novel characters into reusable persona bundles, extract relationship graphs, and let characters speak again with their own personality, stance, bonds, and memory.

[![License: AGPL-3.0-only](https://img.shields.io/badge/License-AGPL--3.0--only-8A2BE2.svg)](LICENSE) · [Website](https://dz3197578117-lab.github.io/zaomeng/) · [中文](README.md)

> This repository continues from upstream Zaomeng 2.0.
> **For what it can do today, see [What Zaomeng can do](docs/features.md)** (grouped by capability, with the version each arrived in);
> per-version history is in [`CHANGELOG.md`](CHANGELOG.md) — **the current version is whatever its top entry says** (no version is hard-coded here, so it cannot drift from the shipped APK).

## What's different in this build

Three things this fork keeps improving on top of upstream 2.0: **it runs offline**, **Chinese model providers work out of the box**, and **both distillation and performance are made solid**.

**For a full capability list, see [What Zaomeng can do](docs/features.md)** (grouped by domain, with the version each arrived in). This table lists only the main changes relative to upstream:

| Change | Notes | Since |
| --- | --- | --- |
| On-device inference | llama.cpp runtime bundled in the APK; import a GGUF and run without an API key | 2.1.2 |
| Five more provider presets | SenseNova, SiliconFlow, Kimi, GLM, MiniMax | 2.1.2 |
| Fetch model list from the endpoint | Falls back to built-in presets on failure | 2.1.2 |
| Relation graph edges, character quality overview, start chapter | 2.1.2 – 2.1.3 |
| Runtime log capture and export | In-app view/copy/export; redacted before export | 2.1.5 |
| Longer generation timeout and retries | 60s → 300s (per-profile override), transient disconnects retried | 2.1.5 |
| **Fixed plugins/director crashing** | Member-extension self-recursion caused a main-thread stack overflow (affected **all** tool flows) | 2.1.6 |
| Director "force execute" | Any director action (including fourth wall) can require the character to comply | 2.1.7 |
| **Per-chapter persona arcs + time-point injection** | Characters no longer start chapter 1 knowing the whole book | 2.1.8 |
| **Persona timeline advances with the story** | One chapter per four turns, derived (not accumulated) so retries cannot double-advance | 2.1.9 |
| **Fixed dialogue crash and duplicated replies** | Pagination prepend had no dedup; duplicate list keys crashed Compose | 2.1.10 |
| Character-name quote normalization; on-device page split out | 2.1.11 |
| **Diagnostics/runtime-log split, capture control, three-layer problem capture** | 2.1.12 |
| **Feature self-check (27 probes)** | Each capability domain registers a probe and reports whether it is healthy; also lists which features have no probe coverage yet | 2.1.13 |
| Smoothness signals | Dropped frames, excessive recomposition, layout jitter, flicker (thresholds still need on-device calibration) | 2.1.13 |
| **Provider content rejection separated from request errors** | A policy rejection is now classified on its own (`dialogue.content_rejected`); chat no longer offers a "retry" that can only fail again | 2.1.14 |
| **Windows support removed** | Desktop ships macOS / Linux only: JNA dark title bar, Windows TTS, the `.exe` lookup branch, the Windows installer config and the Windows claims on the website are gone (a Windows desktop package never actually shipped) | 2.1.14 |
| Storage layer tolerates unrepresentable paths | With a quoted character name in a legacy volume the profile path cannot be represented on some systems; the read used to throw and take the whole dialogue turn down — it is now treated as "not found", leaving the other characters unaffected | 2.1.14 |
| **Built-in model list: tap a name to download and select it** | Four Qwen GGUF presets; download source switchable between the official HuggingFace site and the hf-mirror mirror; every entry labels its **capability provenance** (exact repo / whether the publisher is official / upstream base model / when metadata was read / upstream model card) | 2.1.15 |
| **On-device CPU kernels (Arm KleidiAI)** | The bundled binary carries KleidiAI's runtime-dispatched kernels (dotprod / fp16 / i8mm chosen from the CPU's features, older chips fall back automatically) with the **compile baseline still armv8-a**; 2.1.15 and earlier are the old build without it | 2.1.16 |
| **On-device GPU (Adreno / OpenCL): buildable, not bundled** | The build script can produce the OpenCL backend (upstream lists Snapdragon 8 Elite / Adreno 830 as verified), but it adds a non-system dependency, `libOpenCL.so`. 2.1.16 / 2.1.17 shipped it and **on-device inference failed to load at all** (see 2.1.18); it is no longer bundled pending real-device verification | — |
| Three defects found via a real-device diagnostic | Moderation detection only matched wording and missed the machine-readable `code`; `model_configured` was always false; the local (on-device) profile's `base_url` pointed at a dead port | 2.1.15 |
| **Fixed a crash after setting a chat background** | Image decoding did no downsampling: a 9216×6144 photo is 216 MB, over Android `Canvas`'s 100 MB hard limit — and it threw during **drawing**, where `try` cannot help. Decoding is now two-pass with a sample size derived from a target long edge and a byte cap (216 MB → 3.4 MB); fixing the single decoding entry point also covers avatars, session covers and run-detail images | 2.1.17 |
| **A different voice per character** | Reading picks a voice by speaker: values authored in the persona package (`voice_pitch` / `voice_speed` / `voice_name`) win, otherwise a voice is **derived deterministically from the character name** (the same character always sounds the same). Prosody is then adjusted per line from its **emotion and punctuation** instead of being read flat | 2.1.19 |
| **Offline neural reading (multi-speaker Chinese model)** | A bundled sherpa-onnx engine (arm64-v8a only, ~+30 MB) as an alternative to the system TTS; the model is **not** bundled and is downloaded on demand from the on-device models page (119 MB+, official or hf-mirror source). ⚠️ The neural engine **does not support pitch** (its API takes only a speaker id and speed); speed and volume work | 2.1.19 |

The file-level diff against upstream is in [`docs/changes-from-upstream.md`](docs/changes-from-upstream.md) — **that document is a snapshot from one pass**, so its added / modified / removed counts are frozen at that point and do not grow with the repository; the verification method is documented there too. Per-version detail is in [`CHANGELOG.md`](CHANGELOG.md).

## Maintenance

Moving to a new machine, or continuing maintenance and releases from a fresh environment:

- [`docs/maintainer-setup.md`](docs/maintainer-setup.md) — environment setup, **restoring the release signing key**, a new-machine checklist, and the environment traps actually hit in practice (aapt2 architecture, JVM library pollution, network/DNS)
- [`docs/release-android.md`](docs/release-android.md) — the Android release flow and signing-key management

Scripts (run from the repository root):

```bash
./scripts/check-env.sh            # environment self-check
./scripts/build-android-apk.sh    # one-command APK build
./scripts/check-release-ready.sh  # pre-release self-check
```

## Project layout

| Path | Description | Status |
| --- | --- | --- |
| [`kmp/`](kmp/README.md) | Compose Multiplatform client: shared UI across Android / Desktop with an embedded Ktor + Room backend | ✅ actively maintained |
| `zaomeng-skill/` | OpenClaw / ClawHub skill bundle (CLI / agent environments) | ✅ kept |
| `src/`, `src/web/` | Early Python backend and Web UI | ⛔ **no longer maintained**; kept as a behavioral reference and test baseline |

## Install

### Clients

Prebuilt Android builds are on [Releases](https://github.com/dz3197578117-lab/zaomeng/releases/latest) (`arm64-v8a`, a debug build with the llama.cpp runtime bundled; the version is the latest tag). Release assets are built by GitHub Actions when a tag is pushed, so they no longer depend on a binary committed to the repository. Build instructions for Desktop live in [`kmp/README.md`](kmp/README.md).

> ⚠️ **2.1.4 is a one-time reinstall release**: the signing key used for 2.1.2 / 2.1.3 has been lost, so 2.1.4 cannot be installed over 2.1.3. If you are on 2.1.3, first export each book bundle as `.zaomeng-run.zip` (**into your Downloads folder**), then uninstall and reinstall. From 2.1.4 onward the build is signed with a dedicated, backed-up key, so normal in-place upgrades resume. See [`docs/release-android.md`](docs/release-android.md).

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

## License

[AGPL-3.0](LICENSE)
