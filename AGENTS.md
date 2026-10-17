# Working on EtherDrop Mobile

Follow the existing React Native, Kotlin, and Java patterns. Keep changes simple. The user makes meaningful product and release decisions; ask when the scope or choice is unclear.

## Project

- `App.js`, `src/ui.js`: the desktop Wi-Fi design adapted for phones.
- `src/Updater.js`, `src/updates.js`: GitHub release checks, approval, progress, and bundled release notes.
- `modules/etherdrop/android/`: the native V3 transfer engine, Android document access, foreground service, and APK installer.
- `RELEASE_NOTES.md`: notes for the upcoming release.
- `scripts/prepare_release.py`: automatic release version, Android version code, and bundled notes.
- `.github/workflows/release.yml`: APK compilation on GitHub Actions and GitHub Release publication.

Preserve Windows/Android interoperability, receiver approval, file and folder selection, background transfers, reconnects, diagnostics, and cancellation. iOS implementation remains on hold. Do not add Ethernet controls.

## Updates

The update button belongs only on Home. Check release metadata at startup; downloads require user approval. Download the standalone `EtherDrop.apk`, show progress and speed, check size and available SHA-256 digest, validate its package and newer Android version code, and hand installation to Android. Keep the signing key and package name unchanged. Preserve offline bundled notes, shown once per installed app version.

## Branches and releases

- Work on `main` first. It must contain every commit on `release` and may be ahead of it.
- A push to `main` does not publish an APK. A normal push to `release`, or a manual release workflow run, builds and publishes a patch release.
- Obtain user authorization before committing, pushing, building APKs, or publishing. Authorization already given for the current task can be used; do not ask for it again.
- When publication is authorized, push `main`, fast-forward `release` from `main`, and push `release`. Return the local checkout to `main`.
- The workflow prepares the version, builds, commits the version and notes on `release`, merges into `main`, and pushes both branches and the tag atomically. Preserve newer work on `main`; never force-push to resolve divergence.
- Pull the workflow's version commit after a completed release before starting new work.
- Patch versions are automatic. Major/minor versions require the user's choice. App version, package/lock versions, release tag, bundled notes, and APK version must match. Android `versionCode` increases on every release. Protocol version stays separate.
- Release builds run directly on GitHub Actions using the four `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD` repository secrets. They use the existing signing key from EAS; never generate a replacement key for updates. Development builds may still use EAS. Do not commit credentials. No `EXPO_TOKEN` is needed for the release workflow.
- Setup pushes may use `[skip ci]` to create branches without building or publishing when the user requests setup only. Do not dispatch a release workflow without publication approval.
- After an authorized publication, verify the workflow succeeds and the release contains `EtherDrop.apk` and the correct notes.

## Validation

Use `npm test` for update selection; `python -m unittest discover -s tests -p test_release.py -v` for versioning; and `python -m unittest discover -s tests -p test_updater_download.py -v` with JDK 21 for real HTTP download checks. `npm run export:android` validates JavaScript without building an APK. Native Android compilation and installation remain unverified until the user authorizes a new APK build.

Use `scripts/test-protocol.ps1` when changing the transfer protocol. Inspect changes and preserve unrelated edits. Keep generated folders, credentials, and APKs out of commits. Salesforce metadata does not belong in this repository; fix its creator rather than hiding it with a repository ignore rule.
