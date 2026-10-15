# MasterROM Updater

Update app for MasterROM (Galaxy S20+ / y2s) in a One UI 8.5 glass look.

- Package: `com.masterrom.updater`
- Kotlin + Jetpack Compose, minSdk 31
- Update info: `updater/v2/<codename>.xml` on the `main` branch of `Master37373/MasterROM`

## Build

GitHub Actions (`.github/workflows/build.yml`) builds the release APK on every push to `main`
and signs it with the updater key from the repository secrets `UPDATER_KEY_PK8` and
`UPDATER_KEY_PEM` (base64). The signed APK is attached to the workflow run as an artifact.

Local build: `gradle :app:assembleRelease` (Gradle 8.11, JDK 17, Android SDK 35).

## Status

Stage 1: project, glass components, start screen with update check.
Next: parallel mirror download, verification, TWRP install, settings, widget.
