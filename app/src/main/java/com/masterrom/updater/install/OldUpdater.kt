package com.masterrom.updater.install

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

/**
 * The old ArtisanROM updater (com.artisan.updater) updated itself, so a copy stays in /data/app
 * even after MasterROM stopped shipping it. Remove it silently once (needs DELETE_PACKAGES,
 * granted when MasterROM ships this app as privileged system app).
 */
object OldUpdater {
    private const val PKG = "com.artisan.updater"

    fun removeIfPresent(context: Context) {
        if (context.checkSelfPermission("android.permission.DELETE_PACKAGES") != PackageManager.PERMISSION_GRANTED) return
        val installed = try {
            context.packageManager.getPackageInfo(PKG, 0); true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
        if (!installed) return
        try {
            val sender = PendingIntent.getBroadcast(
                context, 0,
                Intent("com.masterrom.updater.OLD_UPDATER_REMOVED").setPackage(context.packageName),
                PendingIntent.FLAG_IMMUTABLE,
            ).intentSender
            context.packageManager.packageInstaller.uninstall(PKG, sender)
            Log.i("MasterROM", "removing old updater $PKG")
        } catch (e: Exception) {
            Log.i("MasterROM", "could not remove $PKG: $e")
        }
    }
}
