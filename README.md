# MasterROM Updater

The update app of [MasterROM](https://github.com/Master37373/MasterROM) for the Galaxy S20 series
(Exynos 990), in a One UI 8.5 glass look.

> **Based on ArtisanROM.** MasterROM is a personal continuation of
> [ArtisanROM](https://github.com/ArtisanROM/ArtisanROM) by **Android Artisan**, which itself builds on
> [UN1CA](https://github.com/salvogiangri/UN1CA) by **salvogiangri**. This updater replaces the
> ArtisanROM updater in MasterROM and was written from scratch, but the idea and the update
> infrastructure (update XMLs, OTA flow through TWRP) come from ArtisanROM.
> This is **not meant as a kang**: all credit for the ROM base goes to the original authors, see
> [Credits](#credits).

## Features

- Update check against `updater/v2/<device>.xml` of the MasterROM repository, daily in the background
- Fast download from several SourceForge mirrors at once, resume, MD5 check
- Installs ROM updates **without root**: the ZIP is stored in `/data/ota_package`, Android's
  `uncrypt` makes it readable for the recovery, TWRP installs it through an OpenRecoveryScript
- "What's new" from the ROM changelog, own changelog for the app
- Kernel updates (ArtisanKRNL built by the MasterROM kernel workflow), installed the same way
- Updates itself (GitHub releases of this repository)
- Wi-Fi only option, storage overview, hidden developer options (tap "App version" 7 times)

## Requirements in the ROM

The app has to be a privileged system app. MasterROM ships it from `unica/mods/updater` with:

- `/system/priv-app/MasterROMUpdater/MasterROMUpdater.apk`
- privapp permissions `RECOVERY`, `REBOOT`, `ACCESS_CACHE_FILESYSTEM`, `DELETE_PACKAGES`, `INSTALL_PACKAGES`
- an entry in Samsung's `allowed-system-preload-apps.xml`
- the SELinux rule `allow priv_app ota_package_file` (dir + file) for `/data/ota_package`

## Build

GitHub Actions (`.github/workflows/build.yml`) builds and signs the APK on every push to `main`
with the updater key from the secrets `UPDATER_KEY_PK8` / `UPDATER_KEY_PEM` (base64).
`versionName` comes from `version.txt`, `versionCode` is the run number.

To publish a version to the phones, run the workflow by hand with **publish** checked: it creates
the GitHub release `v<version>` with the APK and the notes from `CHANGELOG.md`.

## Credits

- **Android Artisan** and all [ArtisanROM contributors](https://github.com/ArtisanROM/ArtisanROM/graphs/contributors)
  for ArtisanROM, ArtisanKRNL and the original ArtisanROM updater
- **salvogiangri** and all [UN1CA contributors](https://github.com/salvogiangri/UN1CA/graphs/contributors)
  for the UN1CA build system MasterROM is built with
- **TeamWin** for [TWRP](https://twrp.me), **KernelSU-Next** developers, and the **AOSP** project
  (RecoverySystem / uncrypt)
- Everyone who tests MasterROM and reports bugs
