# Release Flow

This file describes the repository-level release paths for maintainers.

There are two independent release tracks:

| Track | Trigger | Guide |
| --- | --- | --- |
| Android app (APK) | pushing a `v*` tag | [`docs/release-android.md`](docs/release-android.md) |
| `zaomeng-skill` package | `python scripts/release_skill.py` | this file, below |

The Android track is automated by [`.github/workflows/release.yml`](.github/workflows/release.yml),
which builds the APK in CI and uploads it to the GitHub Release. It **requires** four
signing secrets (`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`,
`ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`) so every release keeps the same signature and
stays overwrite-installable, plus a matching `EXPECTED_SIGNER_SHA256` in the workflow.
See the guide for the one-time setup, the 2.1.4 one-time reinstall migration, what the
workflow validates, and troubleshooting.

The rest of this file covers the skill package only.
The skill package itself keeps user-facing usage docs under `zaomeng-skill/`.

## Single Version Source

The canonical version source is:

- `zaomeng-skill/.metadata.json`

Everything else should be synchronized from that value.

## Recommended Sequence

Preferred one-command path:

```bash
python scripts/release_skill.py --version <version>
```

This will:

- sync the version across metadata and release-facing docs
- run release guardrails
- package the distributable archive

Manual equivalent:

1. Sync the target version:

```bash
python scripts/sync_skill_version.py --version <version>
```

2. Run release guardrails:

```bash
python scripts/dev_checks.py
```

3. Build the distributable archive:

```bash
python scripts/package_skill.py
```

4. Confirm the output archive name matches:

```text
zaomeng-<version>.skill.zip
```

## What Gets Synchronized

`scripts/sync_skill_version.py` updates:

- `zaomeng-skill/.metadata.json`
- `zaomeng-skill/SKILL.md`
- `zaomeng-skill/README.md`
- `zaomeng-skill/README_EN.md`
- `zaomeng-skill/PUBLISH.md`
- `zaomeng-skill/examples/test-prompts.json`

## What Gets Validated

`scripts/dev_checks.py` covers:

- packaging docs guardrails
- version sync regression checks
- package archive regression checks
- installable skill workflow checks
- mypy on the release helper scripts and prompt-first guardrail modules
- full pytest suite, including unittest-style and pytest-style tests

## Packaging Output

`scripts/package_skill.py` produces:

- `dist/zaomeng-<version>.skill.zip`

The archive filename is derived from the version in `.metadata.json` unless an explicit override is provided.

`scripts/release_skill.py` uses the same archive builder after sync/check steps complete.
