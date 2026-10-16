package com.masterrom.updater.ui.home

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.masterrom.updater.data.DeviceInfo
import com.masterrom.updater.data.KernelUpdate
import com.masterrom.updater.data.NoDeviceException
import com.masterrom.updater.data.RomUpdate
import com.masterrom.updater.data.UpdateRepository
import com.masterrom.updater.data.compareVersions
import com.masterrom.updater.download.DlState
import com.masterrom.updater.download.DownloadJob
import com.masterrom.updater.download.DownloadManager
import com.masterrom.updater.install.Installer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface InstallState {
    data object Idle : InstallState
    /** [progress] 0..1 while Android prepares the update, -1 when unknown. */
    data class Preparing(val progress: Float = -1f) : InstallState
    data class Failed(val reason: Installer.Reason) : InstallState
}

data class DeviceState(val romVersion: String, val kernel: String, val model: String, val codename: String)

sealed interface UpdateState {
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val rom: RomUpdate?, val kernel: KernelUpdate?) : UpdateState
    data class Failed(val noDevice: Boolean, val detail: String) : UpdateState
}

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("updater", Context.MODE_PRIVATE)

    private val _device = MutableStateFlow(DeviceState("", "", "", ""))
    val device: StateFlow<DeviceState> = _device.asStateFlow()

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Checking)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    val download: StateFlow<DlState> = DownloadManager.state

    private val _wifiOnly = MutableStateFlow(DownloadManager.wifiOnly(app))
    val wifiOnly: StateFlow<Boolean> = _wifiOnly.asStateFlow()

    private val _testMode = MutableStateFlow(prefs.getBoolean(KEY_TEST_MODE, false))
    val testMode: StateFlow<Boolean> = _testMode.asStateFlow()

    private val _lastChecked = MutableStateFlow(prefs.getLong(KEY_LAST_CHECK, 0L))
    val lastChecked: StateFlow<Long> = _lastChecked.asStateFlow()

    init {
        viewModelScope.launch {
            _device.value = withContext(Dispatchers.IO) {
                DeviceState(DeviceInfo.romVersion, DeviceInfo.kernel, DeviceInfo.model, DeviceInfo.codename)
            }
            withContext(Dispatchers.IO) { com.masterrom.updater.install.OldUpdater.removeIfPresent(app) }
            // the update was installed: remove the leftovers (the boot receiver usually did this already)
            val staged = Installer.stagedVersion(app)
            if (staged != null && compareVersions(DeviceInfo.romVersion, staged) >= 0) {
                withContext(Dispatchers.IO) {
                    Installer.cleanup()
                    DownloadManager.deleteFiles(app)
                }
                Installer.clearStaged(app)
            }
            check()
        }
    }

    fun check() {
        if (_state.value == UpdateState.Checking && _device.value.codename.isEmpty()) return
        viewModelScope.launch {
            _state.value = UpdateState.Checking
            val started = System.currentTimeMillis()
            val result = try {
                val d = _device.value
                val info = UpdateRepository.fetch(d.codename, test = _testMode.value)
                // A phone still on ArtisanROM (no ro.masterrom.version) gets the first MasterROM
                // release offered although its number (1.0.0) is lower than ArtisanROM's 4.0.x.
                val fromArtisan = DeviceInfo.prop("ro.masterrom.version").isEmpty()
                val rom = info.rom?.takeIf { _testMode.value || fromArtisan || compareVersions(it.version, d.romVersion) > 0 }
                rom?.let { withContext(Dispatchers.IO) { DownloadManager.restore(getApplication<Application>(), DownloadJob.from(it)) } }
                val kernel = info.kernel?.takeIf { k ->
                    val installed = Regex("ArtisanKRNL-v(\\d+(?:\\.\\d+)+)").find(d.kernel)?.groupValues?.get(1)
                    installed != null && compareVersions(k.version, installed) > 0
                }
                val now = System.currentTimeMillis()
                prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
                _lastChecked.value = now
                if (rom != null || kernel != null) UpdateState.Available(rom, kernel) else UpdateState.UpToDate
            } catch (e: NoDeviceException) {
                UpdateState.Failed(noDevice = true, detail = e.codename)
            } catch (e: Exception) {
                UpdateState.Failed(noDevice = false, detail = e.message.orEmpty())
            }
            // keep the checking animation visible for a moment, so the result does not flash
            val elapsed = System.currentTimeMillis() - started
            if (elapsed < MIN_CHECK_MS) delay(MIN_CHECK_MS - elapsed)
            _state.value = result
        }
    }

    fun startDownload() {
        val rom = (_state.value as? UpdateState.Available)?.rom ?: return
        DownloadManager.start(getApplication<Application>(), DownloadJob.from(rom))
    }

    fun pauseDownload() = DownloadManager.pause(getApplication<Application>())

    fun cancelDownload() = DownloadManager.cancel(getApplication<Application>())

    fun setWifiOnly(value: Boolean) {
        DownloadManager.setWifiOnly(getApplication<Application>(), value)
        _wifiOnly.value = value
    }

    /** Test mode (settings, or long press on the ROM version): offers the published build even if it is older. */
    fun toggleTestMode(): Boolean {
        setTestMode(!_testMode.value)
        return _testMode.value
    }

    fun setTestMode(on: Boolean) {
        if (on == _testMode.value) return
        _testMode.value = on
        prefs.edit().putBoolean(KEY_TEST_MODE, on).apply()
        check()
    }

    private val _install = MutableStateFlow<InstallState>(InstallState.Idle)
    val install: StateFlow<InstallState> = _install.asStateFlow()

    /** Moves the verified ZIP into place for TWRP and reboots to recovery. */
    fun installNow() {
        val job = DownloadManager.state.value.job ?: return
        val app = getApplication<Application>()
        val zip = DownloadManager.file(app, job)
        viewModelScope.launch {
            _install.value = InstallState.Preparing()
            Installer.markStaged(app, job.version)
            try {
                if (Installer.canInstallWithoutRoot(app, zip)) {
                    Installer.installWithoutRoot(app, zip) { p -> _install.value = InstallState.Preparing(p) }
                } else {
                    Installer.installAndReboot(zip)
                }
            } catch (e: Installer.InstallException) {
                Installer.clearStaged(app)
                if (e.reason == Installer.Reason.SCRIPT_FAILED || e.reason == Installer.Reason.REBOOT_FAILED) Installer.moveBack(zip)
                _install.value = InstallState.Failed(e.reason)
            }
        }
    }

    fun dismissInstallError() {
        _install.value = InstallState.Idle
    }

    private val _devUnlocked = MutableStateFlow(prefs.getBoolean(KEY_DEV, false))
    /** Developer options are hidden until "App version" in the settings is tapped 7 times. */
    val devUnlocked: StateFlow<Boolean> = _devUnlocked.asStateFlow()

    fun setDevUnlocked(on: Boolean) {
        _devUnlocked.value = on
        prefs.edit().putBoolean(KEY_DEV, on).apply()
        if (!on) setTestMode(false)
    }

    private val _storedBytes = MutableStateFlow(0L)
    /** Space used by downloaded update files. */
    val storedBytes: StateFlow<Long> = _storedBytes.asStateFlow()

    fun refreshStorage() {
        viewModelScope.launch(Dispatchers.IO) {
            // allocated blocks, so a half-finished (sparse) download shows its real size
            _storedBytes.value = DownloadManager.dir(getApplication<Application>()).listFiles()
                ?.sumOf { f -> runCatching { android.system.Os.stat(f.path).st_blocks * 512L }.getOrDefault(f.length()) } ?: 0L
        }
    }

    fun deleteDownloads() {
        DownloadManager.cancel(getApplication<Application>())
        refreshStorage()
    }

    private companion object {
        const val KEY_TEST_MODE = "test_mode"
        const val KEY_DEV = "dev_unlocked"
        const val KEY_LAST_CHECK = "last_check"
        const val MIN_CHECK_MS = 900L
    }
}
