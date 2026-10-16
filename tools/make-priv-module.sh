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
        <permission name="android.permission.ACCESS_CACHE_FILESYSTEM" />
    </privapp-permissions>
</permissions>
XML
# KernelSU-Next on this ROM does not mount module files into /system by itself (no
# metamodule), so the module mounts its two folders with overlayfs before Android starts.
cat > "$W/post-fs-data.sh" <<'SH'
#!/system/bin/sh
# overlayfs does not accept /data (f2fs) as a layer, so the files are copied to a small tmpfs
# first and the overlay is built from there.
MODDIR=${0%/*}
T=/dev/masterrom_updater
mkdir -p "$T"
grep -q " $T tmpfs " /proc/mounts || mount -t tmpfs -o mode=755 masterrom_updater "$T"
rm -rf "$T/system"
cp -a "$MODDIR/system" "$T/"
chcon -R u:object_r:system_file:s0 "$T/system"
for d in priv-app etc/permissions; do
    grep -q " /system/$d overlay " /proc/mounts && continue
    if mount -t overlay -o "lowerdir=$T/system/$d:/system/$d" masterrom_updater "/system/$d"; then
        log -t MasterROM "mounted /system/$d"
    else
        log -t MasterROM "mount /system/$d failed"
    fi
done
SH
chmod 755 "$W/post-fs-data.sh"
# no automatic mount by KernelSU, the script above does it
touch "$W/skip_mount"
rm -f "$OUT"
(cd "$W" && zip -qr "$OUT" .)
rm -rf "$W"
echo "$OUT"
