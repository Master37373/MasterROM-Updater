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
        com.masterrom.updater.check.UpdateCheckJob.schedule(context)
        // a flashed (or failed) kernel zip is never needed again: it is small and downloaded fresh
        com.masterrom.updater.data.KernelDownload.deleteAll(DownloadManager.dir(context))
        val staged = Installer.stagedVersion(context) ?: return
        // only clean up when the update really was installed; otherwise keep the ZIP for a retry
        if (com.masterrom.updater.data.compareVersions(com.masterrom.updater.data.DeviceInfo.romVersion, staged) < 0) {
            Installer.clearStaged(context)
            return
        }
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
