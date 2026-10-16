#!/bin/sh
# Builds a KernelSU/Magisk module that puts the updater APK into /system/priv-app with the
# privapp-permissions it needs (RECOVERY, REBOOT). For testing the root-free install before
# the updater is built into the ROM.  Usage: tools/make-priv-module.sh path/to/MasterROM-Updater.apk
set -e
APK="$1"
[ -f "$APK" ] || { echo "usage: $0 MasterROM-Updater.apk"; exit 1; }
OUT="$(pwd)/MasterROMUpdater-privapp.zip"
W="$(mktemp -d)"
mkdir -p "$W/system/priv-app/MasterROMUpdater" "$W/system/etc/permissions"
cp "$APK" "$W/system/priv-app/MasterROMUpdater/MasterROMUpdater.apk"
cat > "$W/module.prop" <<PROP
id=masterrom_updater_privapp
name=MasterROM Updater (system app test)
version=1
versionCode=1
author=Master37373
description=Installs MasterROM Updater as privileged system app so updates install without root.
PROP
cat > "$W/system/etc/permissions/privapp-permissions-com.masterrom.updater.xml" <<XML
<?xml version="1.0" encoding="utf-8"?>
<permissions>
    <privapp-permissions package="com.masterrom.updater">
        <permission name="android.permission.RECOVERY" />
        <permission name="android.permission.REBOOT" />
    </privapp-permissions>
</permissions>
XML
rm -f "$OUT"
(cd "$W" && zip -qr "$OUT" .)
rm -rf "$W"
echo "$OUT"
