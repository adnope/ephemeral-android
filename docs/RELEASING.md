# Releasing

This project uses a reviewable release PR workflow.

## Versioning

The app version lives in `version.properties`:

```properties
VERSION_NAME=0.2.2
```

Gradle derives Android `versionCode` automatically from `VERSION_NAME`:

```text
major * 1,000,000 + minor * 10,000 + patch * 100
```

Examples:

```text
0.2.2 -> 20200
0.3.0 -> 30000
1.0.0 -> 1000000
```

Do not edit `versionCode` manually.

## One-time GitHub setup

Add these repository secrets before publishing releases:

```text
RELEASE_KEYSTORE_BASE64
RELEASE_KEYSTORE_PASSWORD
RELEASE_KEY_ALIAS
RELEASE_KEY_PASSWORD
```

Create `RELEASE_KEYSTORE_BASE64` from your release keystore:

```sh
base64 -w 0 path/to/release-keystore.jks
```

Use the same keystore and alias you use for local `installRelease` builds.

## Prepare a release

1. Open GitHub Actions.
2. Run `Prepare Release`.
3. Enter the next version, for example `0.3.0`.
4. Optionally paste curated Markdown release notes. If you leave it empty, the workflow drafts notes from commits since the latest release tag.
5. Review the generated release PR.
6. Edit `docs/releases/<version>.md` if you want more polished notes.
7. Merge the release PR.

## Publish a release

1. Open GitHub Actions.
2. Run `Publish Release` on `main`.
3. Leave `version` empty to publish the current `version.properties` version.
4. The workflow builds a signed release APK, creates the git tag, creates the GitHub Release, and attaches the APK.

## AI-assisted release notes

Ask an AI agent to rewrite `docs/releases/<version>.md` using:

```sh
git log <previous-version>..HEAD --oneline --no-merges
git diff <previous-version>..HEAD
```

Keep notes user-facing. Prefer sections like:

```md
## Highlights
- Added download progress for Chat and History downloads.

## Improvements
- Shows aggregate progress when downloading multiple selected items.

## Verification
- Built and installed the release APK on a connected Android device.
```
