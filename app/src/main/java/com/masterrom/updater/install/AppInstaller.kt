package com.masterrom.updater.install

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/**
 * Installs a new version of this app. As privileged system app (INSTALL_PACKAGES) it updates
 * itself silently; otherwise Android shows its normal install confirmation.
 */
object AppInstaller {
    /** Confirmation screen Android wants to show; the activity in the foreground launches it. */
    val confirm = MutableStateFlow<Intent?>(null)

    /** Set when Android rejected the update (status message, may be empty). */
    val failure = MutableStateFlow<String?>(null)

    fun install(context: Context, apk: File) {
        val pi = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val status = PendingIntent.getBroadcast(
                context, id,
                Intent(context, InstallResultReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(status.intentSender)
        }
        Log.i("MasterROM", "self-update session $id committed")
    }
}

/** Receives the result of the self-update session; asks the user when Android needs a confirmation. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        Log.i("MasterROM", "self-update status $status $message")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                // A receiver may not start activities from the background (Android 10+):
                // hand the confirmation to the visible activity instead.
                AppInstaller.confirm.value = confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // the app is restarted by the system
            else -> AppInstaller.failure.value = message.orEmpty().ifEmpty { "status $status" }
        }
    }
}
