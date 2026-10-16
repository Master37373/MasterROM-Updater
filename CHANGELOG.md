# 1.2.1
- Time of the night install: iOS-style scroll wheels instead of the clock
- The time no longer breaks into two lines
- "This device" shows ROM, kernel and device again (system, security patch and build number removed)

# 1.2.0
- "Looking for something else?": link back to the MasterROM settings
- More about this device: One UI and Android version, security patch, build number
- Download notification: cancel while downloading; continue or cancel when paused (also with the app closed)
- The time of the night install can be chosen in the settings
- After installing a picked ZIP the updater tells whether it worked (ROM and kernel ZIPs)

# 1.1.1
- Clearer settings: short texts, rows with dividers, tap anywhere on a row to switch it; "Wi-Fi only" is now "Use mobile data"

# 1.1.0
- Download automatically over Wi-Fi: new MasterROM versions are downloaded by themselves (Settings)
- Install tonight: a downloaded update can be installed at 2:00 at night, also when the app is closed (skipped when the battery is below 30 % and not charging)
- After the restart the updater tells whether the update or kernel was installed, with what's new
- MasterROM version history with all versions and their changes
- Install a ZIP: any ROM, kernel or TWRP ZIP from the storage can be installed like an update, without root
- ZIP check before installing: signed by MasterROM or re-signed / unsigned (with a warning), the version inside compared with the installed one, and whether it is for this phone

# 1.0.5
- Page titles shrink instead of fading: the large centred title shrinks smoothly into the bar while scrolling and grows back at the top of the page

# 1.0.4
- Page titles work like in One UI: a collapsing app bar with a large title at the top of the page
- Pause and resume continue byte-exactly where the download stopped, nothing is downloaded twice
- Fixed: resuming started the whole download again (the progress could not be saved in the OTA folder)
- Resuming within 10 minutes skips the server search

# 1.0.3
- Updater self-update: Android's install confirmation is shown again, and a failed update no longer hangs at "Installing"

# 1.0.2
- Centred page titles: they shrink and fade out while scrolling down and come back when scrolling up

# 1.0.1
- Cancel and delete buttons are red, pause is orange
- "What's new" for ROM updates, taken from the MasterROM changelog
- Daily update check in the background with a notification (can be turned off in the settings)
- Updater changelog in the settings
- The updater updates itself: new versions are shown on the start screen and installed with one tap
- Kernel updates: new kernel builds are offered on their own and installed with one tap, without root

# 1.0.0
- Initial release
