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

sealed interface SelfUpdateState {
    data object Idle : SelfUpdateState
    data class Downloading(val progress: Float) : SelfUpdateState
    data object Installing : SelfUpdateState
    data object Failed : SelfUpdateState
}

sealed interface KernelState {
    data object Idle : KernelState
    data class Downloading(val progress: Float) : KernelState
    /** [progress] 0..1 while Android prepares the install, -1 when unknown. */
    data class Preparing(val progress: Float = -1f) : KernelState
    data object Failed : KernelState
}

sealed interface InstallState {
    data object Idle : InstallState
    /** [progress] 0..1 while Android prepares the update, -1 when unknown. */
    data class Preparing(val progress: Float = -1f) : InstallState
    data class Failed(val reason: Installer.Reason) : InstallState
}

sealed interface LocalZipState {
    data object Idle : LocalZipState
    data class Copying(val name: String, val progress: Float) : LocalZipState
    /** The signature and version check; [progress] 0..1 (reads the whole file). */
    data class Checking(val name: String, val progress: Float) : LocalZipState
    data class Ready(val name: String, val size: Long, val check: com.masterrom.updater.install.LocalZip.Check) : LocalZipState
    data class Preparing(val progress: Float = -1f) : LocalZipState
    data class Failed(val reason: com.masterrom.updater.install.LocalZip.Reason?) : LocalZipState
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
            com.masterrom.updater.check.UpdateCheckJob.schedule(app)
            // result of the last install (the boot receiver usually evaluated it already)
            val result = withContext(Dispatchers.IO) { com.masterrom.updater.install.PostUpdate.evaluate(app) }
            if (result?.kind == com.masterrom.updater.install.PostUpdate.Kind.ROM_OK) {
                withContext(Dispatchers.IO) {
                    Installer.cleanup()
                    DownloadManager.deleteFiles(app)
                }
                Installer.clearStaged(app)
            }
            loadPostUpdate()
            check()
        }
    }

    init {
        viewModelScope.launch {
            com.masterrom.updater.install.AppInstaller.failure.collect { f ->
                if (f != null) {
                    android.util.Log.i("MasterROM", "self-update rejected: $f")
                    _selfUpdate.value = SelfUpdateState.Failed
                    com.masterrom.updater.install.AppInstaller.failure.value = null
                }
            }
        }
    }

    fun check() {
        if (_state.value == UpdateState.Checking && _device.value.codename.isEmpty()) return
        viewModelScope.launch {
            _appRelease.value = try { com.masterrom.updater.data.AppUpdate.check() } catch (e: Exception) { null }
        }
        viewModelScope.launch {
            _state.value = UpdateState.Checking
            val started = System.currentTimeMillis()
            val result = try {
                val d = _device.value
                val info = UpdateRepository.fetch(d.codename, test = _testMode.value)
                // A phone still on ArtisanROM (no ro.masterrom.version) gets the first MasterROM
                // release offered although its number (1.0.0) is lower than ArtisanROM's 4.0.x.
                val fromArtisan = DeviceInfo.prop("ro.masterrom.version").isEmpty()
                val rom = info.rom?.takeIf {
                    _testMode.value || (fromArtisan && it.filename.startsWith("MasterROM")) ||
                        (!fromArtisan && compareVersions(it.version, d.romVersion) > 0)
                }
                rom?.let { withContext(Dispatchers.IO) { DownloadManager.restore(getApplication<Application>(), DownloadJob.from(it)) } }
                rom?.let { r -> viewModelScope.launch { _changelog.value = com.masterrom.updater.data.Changelog.forVersion(r.version) } }
                // A ROM update brings its own kernel, so a kernel update is only offered on its own.
                val kernel = info.kernel?.takeIf { k ->
                    rom == null && k.link.isNotEmpty() &&
                        (_testMode.value || com.masterrom.updater.data.KernelVersion.isNewer(k.version, d.kernel))
                }
                _kernel.value = kernel
                if (kernel == null && _kernelState.value == KernelState.Failed) _kernelState.value = KernelState.Idle
                val now = System.currentTimeMillis()
                prefs.edit().putLong(KEY_LAST_CHECK, now).apply()
                _lastChecked.value = now
                if (rom != null) UpdateState.Available(rom, null) else UpdateState.UpToDate
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

    fun cancelDownload() {
        cancelNightInstall()
        DownloadManager.cancel(getApplication<Application>())
    }

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
        cancelNightInstall()
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

    private val _kernel = MutableStateFlow<KernelUpdate?>(null)
    /** A newer kernel build, offered when there is no ROM update. */
    val kernel: StateFlow<KernelUpdate?> = _kernel.asStateFlow()

    private val _kernelState = MutableStateFlow<KernelState>(KernelState.Idle)
    val kernelState: StateFlow<KernelState> = _kernelState.asStateFlow()

    /** Downloads the kernel zip, then installs it through TWRP like a ROM update (phone reboots). */
    fun installKernel() {
        val k = _kernel.value ?: return
        if (_kernelState.value is KernelState.Downloading || _kernelState.value is KernelState.Preparing) return
        val app = getApplication<Application>()
        viewModelScope.launch {
            _kernelState.value = KernelState.Downloading(0f)
            val dir = DownloadManager.dir(app)
            try {
                val zip = com.masterrom.updater.data.KernelDownload.download(k, dir) { p ->
                    _kernelState.value = KernelState.Downloading(p)
                }
                _kernelState.value = KernelState.Preparing()
                com.masterrom.updater.install.PostUpdate.markKernel(app, k.version)
                if (Installer.canInstallWithoutRoot(app, zip)) {
                    Installer.installWithoutRoot(app, zip) { p -> _kernelState.value = KernelState.Preparing(p) }
                } else {
                    Installer.installAndReboot(zip)
                }
            } catch (e: Exception) {
                android.util.Log.i("MasterROM", "kernel install failed: $e")
                com.masterrom.updater.install.PostUpdate.clearKernel(app)
                withContext(Dispatchers.IO) { com.masterrom.updater.data.KernelDownload.deleteAll(dir) }
                _kernelState.value = KernelState.Failed
            }
        }
    }

    private val _changelog = MutableStateFlow<List<String>>(emptyList())
    /** "What's new" lines for the offered ROM update (lines starting with § are sub-headings). */
    val changelog: StateFlow<List<String>> = _changelog.asStateFlow()

    private val _autoCheck = MutableStateFlow(com.masterrom.updater.check.UpdateCheckJob.isEnabled(app))
    val autoCheck: StateFlow<Boolean> = _autoCheck.asStateFlow()

    fun setAutoCheck(on: Boolean) {
        com.masterrom.updater.check.UpdateCheckJob.setEnabled(getApplication<Application>(), on)
        _autoCheck.value = on
    }

    private val _appRelease = MutableStateFlow<com.masterrom.updater.data.AppRelease?>(null)
    /** A newer version of this app, if one is published. */
    val appRelease: StateFlow<com.masterrom.updater.data.AppRelease?> = _appRelease.asStateFlow()

    private val _selfUpdate = MutableStateFlow<SelfUpdateState>(SelfUpdateState.Idle)
    val selfUpdate: StateFlow<SelfUpdateState> = _selfUpdate.asStateFlow()

    fun updateApp() {
        val release = _appRelease.value ?: return
        if (_selfUpdate.value is SelfUpdateState.Downloading || _selfUpdate.value == SelfUpdateState.Installing) return
        val app = getApplication<Application>()
        viewModelScope.launch {
            _selfUpdate.value = SelfUpdateState.Downloading(0f)
            try {
                val apk = com.masterrom.updater.data.AppUpdate.download(release, app.cacheDir) { p ->
                    _selfUpdate.value = SelfUpdateState.Downloading(p)
                }
                _selfUpdate.value = SelfUpdateState.Installing
                withContext(Dispatchers.IO) { com.masterrom.updater.install.AppInstaller.install(app, apk) }
                // no answer at all (e.g. the confirmation was dismissed): allow another try
                delay(120_000)
                if (_selfUpdate.value == SelfUpdateState.Installing) _selfUpdate.value = SelfUpdateState.Idle
            } catch (e: Exception) {
                android.util.Log.i("MasterROM", "self-update failed: $e")
                _selfUpdate.value = SelfUpdateState.Failed
            }
        }
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
        cancelNightInstall()
        DownloadManager.cancel(getApplication<Application>())
        refreshStorage()
    }

    // ---- automatic download and night install ----

    private val _autoDownload = MutableStateFlow(com.masterrom.updater.schedule.Schedule.autoDownload(app))
    val autoDownload: StateFlow<Boolean> = _autoDownload.asStateFlow()

    /** Automatic download needs the daily check, so turning it on turns that on too. */
    fun setAutoDownload(on: Boolean) {
        com.masterrom.updater.schedule.Schedule.setAutoDownload(getApplication<Application>(), on)
        _autoDownload.value = on
        if (on && !_autoCheck.value) setAutoCheck(true)
    }

    private val _nightAt = MutableStateFlow(com.masterrom.updater.schedule.Schedule.nightInstallAt(app))
    /** Time of the planned night install, 0 when none is planned. */
    val nightAt: StateFlow<Long> = _nightAt.asStateFlow()

    fun planNightInstall() {
        val job = DownloadManager.state.value.job ?: return
        _nightAt.value = com.masterrom.updater.schedule.Schedule.planNightInstall(getApplication<Application>(), job)
    }

    private val _nightMinutes = MutableStateFlow(com.masterrom.updater.schedule.Schedule.nightMinutes(app))
    /** Time of day of the night install, minutes after midnight. */
    val nightMinutes: StateFlow<Int> = _nightMinutes.asStateFlow()

    fun setNightMinutes(minutes: Int) {
        val app = getApplication<Application>()
        com.masterrom.updater.schedule.Schedule.setNightMinutes(app, minutes)
        _nightMinutes.value = com.masterrom.updater.schedule.Schedule.nightMinutes(app)
        _nightAt.value = com.masterrom.updater.schedule.Schedule.nightInstallAt(app)
    }

    fun cancelNightInstall() {
        com.masterrom.updater.schedule.Schedule.cancelNightInstall(getApplication<Application>())
        _nightAt.value = 0L
    }

    // ---- after an install ----

    private val _postUpdate = MutableStateFlow<com.masterrom.updater.install.PostUpdate.Result?>(null)
    /** Result of the last install, shown once on the start screen. */
    val postUpdate: StateFlow<com.masterrom.updater.install.PostUpdate.Result?> = _postUpdate.asStateFlow()

    private val _postChangelog = MutableStateFlow<List<String>>(emptyList())
    /** What's new in the version that was just installed. */
    val postChangelog: StateFlow<List<String>> = _postChangelog.asStateFlow()

    private fun loadPostUpdate() {
        val r = com.masterrom.updater.install.PostUpdate.pending(getApplication<Application>())
        _postUpdate.value = r
        if (r?.kind == com.masterrom.updater.install.PostUpdate.Kind.ROM_OK) {
            viewModelScope.launch { _postChangelog.value = com.masterrom.updater.data.Changelog.forVersion(r.version) }
        }
    }

    fun dismissPostUpdate() {
        com.masterrom.updater.install.PostUpdate.dismiss(getApplication<Application>())
        _postUpdate.value = null
    }

    // ---- install a ZIP the user picked ----

    private val _localZip = MutableStateFlow<LocalZipState>(LocalZipState.Idle)
    val localZip: StateFlow<LocalZipState> = _localZip.asStateFlow()
    private var localFile: java.io.File? = null
    private var localJob: kotlinx.coroutines.Job? = null

    fun pickLocalZip(uri: android.net.Uri) {
        if (_localZip.value is LocalZipState.Copying || _localZip.value is LocalZipState.Checking || _localZip.value is LocalZipState.Preparing) return
        val app = getApplication<Application>()
        localJob = viewModelScope.launch {
            val info = withContext(Dispatchers.IO) {
                runCatching { com.masterrom.updater.install.LocalZip.info(app, uri) }.getOrNull()
            }
            if (info == null) {
                _localZip.value = LocalZipState.Failed(com.masterrom.updater.install.LocalZip.Reason.READ_FAILED)
                return@launch
            }
            _localZip.value = LocalZipState.Copying(info.name, 0f)
            try {
                val (file, md5) = com.masterrom.updater.install.LocalZip.copy(app, uri, info) { p ->
                    _localZip.value = LocalZipState.Copying(info.name, p)
                }
                _localZip.value = LocalZipState.Checking(info.name, 0f)
                val check = try {
                    com.masterrom.updater.install.LocalZip.check(app, file, md5) { p ->
                        _localZip.value = LocalZipState.Checking(info.name, p)
                    }
                } catch (e: Exception) {
                    file.delete()
                    throw e
                }
                localFile = file
                _localZip.value = LocalZipState.Ready(info.name, file.length(), check)
            } catch (e: com.masterrom.updater.install.LocalZip.LocalZipException) {
                _localZip.value = LocalZipState.Failed(e.reason)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.i("MasterROM", "local zip failed: $e")
                _localZip.value = LocalZipState.Failed(com.masterrom.updater.install.LocalZip.Reason.READ_FAILED)
            }
        }
    }

    fun installLocalZip() {
        val zip = localFile ?: return
        val ready = _localZip.value as? LocalZipState.Ready ?: return
        val app = getApplication<Application>()
        viewModelScope.launch {
            _localZip.value = LocalZipState.Preparing()
            com.masterrom.updater.install.PostUpdate.markLocal(app, ready.check.kind, ready.check.version, ready.name)
            try {
                if (Installer.canInstallWithoutRoot(app, zip)) {
                    Installer.installWithoutRoot(app, zip) { p -> _localZip.value = LocalZipState.Preparing(p) }
                } else {
                    Installer.installAndReboot(zip)
                }
            } catch (e: Exception) {
                android.util.Log.i("MasterROM", "local zip install failed: $e")
                com.masterrom.updater.install.PostUpdate.clearLocal(app)
                _localZip.value = LocalZipState.Failed(null)
            }
        }
    }

    /** Stops copying / forgets the picked ZIP and deletes the copy. */
    fun clearLocalZip() {
        localJob?.cancel()
        localFile = null
        _localZip.value = LocalZipState.Idle
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) { com.masterrom.updater.install.LocalZip.deleteAll(app) }
    }

    private companion object {
        const val KEY_TEST_MODE = "test_mode"
        const val KEY_DEV = "dev_unlocked"
        const val KEY_LAST_CHECK = "last_check"
        const val MIN_CHECK_MS = 900L
    }
}
