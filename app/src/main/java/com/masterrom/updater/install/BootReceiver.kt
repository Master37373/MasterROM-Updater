package com.masterrom.updater.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.masterrom.updater.download.DownloadManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** After the phone has booted the installed update, removes the recovery scripts and the ZIP. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (Installer.stagedVersion(context) == null) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Installer.cleanup()
                DownloadManager.deleteFiles(context)
                Installer.clearStaged(context)
            } finally {
                pending.finish()
            }
        }
    }
}
