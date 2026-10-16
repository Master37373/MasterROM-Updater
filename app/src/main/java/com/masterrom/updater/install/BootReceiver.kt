package com.masterrom.updater.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.masterrom.updater.download.DownloadManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * After a boot: tells whether the last install worked, removes the recovery scripts and the ZIP
 * of an installed update, and plans a pending night install again.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        com.masterrom.updater.check.UpdateCheckJob.schedule(context)
        com.masterrom.updater.schedule.Schedule.restore(context)
        val dir = DownloadManager.dir(context)
        // a flashed (or failed) kernel zip is never needed again: it is small and downloaded fresh
        com.masterrom.updater.data.KernelDownload.deleteAll(dir)
        // a copied local ZIP is not kept either
        dir.listFiles()?.forEach { if (it.name.startsWith(LocalZip.PREFIX)) it.delete() }

        val result = PostUpdate.evaluate(context) ?: return
        PostUpdate.notify(context, result)
        if (result.kind != PostUpdate.Kind.ROM_OK) return   // failed: keep the ZIP for another try
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
