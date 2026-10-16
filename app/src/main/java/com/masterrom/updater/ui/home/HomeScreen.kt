package com.masterrom.updater.ui.home

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.StatFs
import android.text.format.DateUtils
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.masterrom.updater.R
import com.masterrom.updater.data.DeviceInfo
import com.masterrom.updater.data.RomUpdate
import com.masterrom.updater.download.DlError
import com.masterrom.updater.download.DlState
import com.masterrom.updater.download.DownloadManager
import com.masterrom.updater.download.Phase
import com.masterrom.updater.install.Installer
import com.masterrom.updater.ui.glass.Chip
import com.masterrom.updater.ui.glass.ChipRow
import com.masterrom.updater.ui.glass.CollapsingScreen
import com.masterrom.updater.ui.glass.GlassBackground
import com.masterrom.updater.ui.glass.GlassCard
import com.masterrom.updater.ui.glass.GlassIconButton
import com.masterrom.updater.ui.glass.HeroIcon
import com.masterrom.updater.ui.glass.HeroKind
import com.masterrom.updater.ui.glass.InfoRow
import com.masterrom.updater.ui.glass.LargeTitle
import com.masterrom.updater.ui.glass.PrimaryButton
import com.masterrom.updater.ui.glass.ProgressRing
import com.masterrom.updater.ui.glass.SecondaryButton
import com.masterrom.updater.ui.glass.SectionLabel
import com.masterrom.updater.ui.glass.TintButton
import com.masterrom.updater.ui.theme.LocalGlass

@Composable
fun HomeScreen(vm: HomeViewModel = viewModel(), onOpenSettings: () -> Unit = {}) {
    val state by vm.state.collectAsStateWithLifecycle()
    val device by vm.device.collectAsStateWithLifecycle()
    val lastChecked by vm.lastChecked.collectAsStateWithLifecycle()
    val download by vm.download.collectAsStateWithLifecycle()
    val testMode by vm.testMode.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val unknown = stringResource(R.string.unknown)
    var confirmCancel by remember { mutableStateOf(false) }
    var confirmInstall by remember { mutableStateOf(false) }
    val installState by vm.install.collectAsStateWithLifecycle()

    // Notifications are optional: the download starts whether or not the permission is granted.
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.startDownload() }
    val startDownload: () -> Unit = {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            vm.startDownload()
        }
    }

    CollapsingScreen(title = stringResource(R.string.title_software_update)) {

            // Which card is shown: the check result, or the download once one exists for the offered build.
            val avail = state as? UpdateState.Available
            val dlForOffer = avail?.rom != null && download.job?.version == avail.rom.version && download.phase != Phase.IDLE
            val cardKey: Any = if (dlForOffer) "dl-${download.phase}" else state::class

            Box(Modifier.fillMaxWidth()) {
            AnimatedContent(
                targetState = cardKey,
                transitionSpec = {
                    (fadeIn(tween(350)) + scaleIn(tween(350), initialScale = 0.96f)) togetherWith fadeOut(tween(200))
                },
                label = "status",
            ) { key ->
                GlassCard {
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        val s = state
                        if (key is String && avail?.rom != null) {
                            DownloadContent(
                                rom = avail.rom,
                                dl = download,
                                onResume = startDownload,
                                onPause = vm::pauseDownload,
                                onCancel = { confirmCancel = true },
                                install = installState,
                                onInstall = { confirmInstall = true },
                            )
                        } else {
                            when (s) {
                                UpdateState.Checking -> {
                                    HeroIcon(HeroKind.CHECKING)
                                    Headline(stringResource(R.string.checking))
                                }
                                UpdateState.UpToDate -> {
                                    HeroIcon(HeroKind.UP_TO_DATE)
                                    Headline(stringResource(R.string.up_to_date))
                                    Body(
                                        stringResource(R.string.rom_and_device, "MasterROM " + device.romVersion.ifEmpty { unknown }, device.model) +
                                            "\n" + lastCheckedText(lastChecked),
                                    )
                                    SecondaryButton(stringResource(R.string.check_now), onClick = vm::check)
                                }
                                is UpdateState.Available -> {
                                    HeroIcon(HeroKind.AVAILABLE)
                                    if (s.rom != null) {
                                        Headline(stringResource(R.string.update_available))
                                        Text("MasterROM " + s.rom.version, color = LocalGlass.current.text, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold)
                                        Body(stringResource(R.string.installed_version, device.romVersion.ifEmpty { unknown }))
                                        ChipRow {
                                            if (s.rom.size > 0) Chip(Formatter.formatShortFileSize(context, s.rom.size))
                                            if (s.rom.date.isNotEmpty()) Chip(s.rom.date)
                                            if (s.rom.cleanFlash) Chip(stringResource(R.string.chip_clean_flash), LocalGlass.current.warning)
                                            else Chip(stringResource(R.string.chip_dirty_flash))
                                        }
                                        if (testMode) Chip(stringResource(R.string.chip_test), LocalGlass.current.warning)
                                        Spacer(Modifier.height(4.dp))
                                        PrimaryButton(stringResource(R.string.download), onClick = startDownload, modifier = Modifier.fillMaxWidth())
                                    } else if (s.kernel != null) {
                                        Headline(stringResource(R.string.kernel_update_available))
                                        Text(s.kernel.version, color = LocalGlass.current.text, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold)
                                    }
                                }
                                is UpdateState.Failed -> {
                                    HeroIcon(HeroKind.ERROR)
                                    Headline(stringResource(R.string.check_failed))
                                    Body(
                                        if (s.noDevice) stringResource(R.string.error_no_device, s.detail)
                                        else stringResource(R.string.error_network),
                                    )
                                    SecondaryButton(stringResource(R.string.try_again), onClick = vm::check)
                                }
                            }
                        }
                    }
                }
            }

            // settings button in the top right corner of the status card
            GlassIconButton(
                painterResource(R.drawable.ic_settings),
                contentDescription = stringResource(R.string.section_settings),
                onClick = onOpenSettings,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 12.dp),
            )
            }

            // newer version of this app
            val appRelease by vm.appRelease.collectAsStateWithLifecycle()
            val selfUpdate by vm.selfUpdate.collectAsStateWithLifecycle()
            appRelease?.let { release ->
                SectionLabel(stringResource(R.string.app_update_section))
                AppUpdateCard(release, selfUpdate, onUpdate = vm::updateApp)
            }

            // "What's new" for the offered update
            val changelog by vm.changelog.collectAsStateWithLifecycle()
            val offered = (state as? UpdateState.Available)?.rom
            if (offered != null && changelog.isNotEmpty()) {
                SectionLabel(stringResource(R.string.whats_new) + " · " + offered.version)
                ChangelogCard(changelog)
            }

            SectionLabel(stringResource(R.string.section_device))
            GlassCard(padding = PaddingValues(horizontal = 22.dp, vertical = 6.dp)) {
                InfoRow(
                    stringResource(R.string.row_rom_version),
                    device.romVersion.ifEmpty { unknown },
                    divider = false,
                )
                InfoRow(stringResource(R.string.row_kernel), DeviceInfo.shortKernel(device.kernel).ifEmpty { unknown }, divider = true)
                InfoRow(stringResource(R.string.row_device), device.model + if (device.codename.isNotEmpty()) " (${device.codename})" else "", divider = true)
            }

    }

    if (confirmInstall) {
        val rom = (state as? UpdateState.Available)?.rom
        val battery = remember { batteryLevel(context) }
        val lowBattery = battery.first < 30 && !battery.second
        AlertDialog(
            onDismissRequest = { confirmInstall = false },
            title = { Text(stringResource(R.string.install_title, rom?.version.orEmpty())) },
            text = {
                Text(
                    when {
                        lowBattery -> stringResource(R.string.install_battery_low, battery.first)
                        rom?.cleanFlash == true -> stringResource(R.string.install_text) + "\n\n" + stringResource(R.string.install_clean_warning)
                        else -> stringResource(R.string.install_text)
                    },
                )
            },
            confirmButton = {
                if (!lowBattery) {
                    TextButton(onClick = { confirmInstall = false; vm.installNow() }) {
                        Text(stringResource(R.string.install_confirm))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmInstall = false }) { Text(stringResource(R.string.cancel_no)) }
            },
            shape = RoundedCornerShape(28.dp),
        )
    }

    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text(stringResource(R.string.cancel_title)) },
            text = { Text(stringResource(R.string.cancel_text)) },
            confirmButton = {
                TextButton(onClick = { confirmCancel = false; vm.cancelDownload() }) {
                    Text(stringResource(R.string.cancel_yes), color = LocalGlass.current.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmCancel = false }) { Text(stringResource(R.string.cancel_no)) }
            },
            shape = RoundedCornerShape(28.dp),
        )
    }
}

/** Card content for a download in any phase (searching servers, running, paused, verifying, done, failed). */
@Composable
private fun DownloadContent(
    rom: RomUpdate,
    dl: DlState,
    onResume: () -> Unit,
    onPause: () -> Unit,
    onCancel: () -> Unit,
    install: InstallState,
    onInstall: () -> Unit,
) {
    val g = LocalGlass.current
    val context = LocalContext.current
    val sizeOf = { b: Long -> Formatter.formatShortFileSize(context, b) }
    val progressText = stringResource(R.string.progress_of, sizeOf(dl.done), sizeOf(dl.total))

    when (dl.phase) {
        Phase.PREPARING -> {
            ProgressRing(-1f)
            Headline(stringResource(R.string.searching_servers))
            Body("MasterROM ${rom.version} · ${sizeOf(rom.size)}")
            TintButton(stringResource(R.string.cancel), g.error, onClick = onCancel)
        }
        Phase.RUNNING -> {
            ProgressRing(dl.progress, caption = stringResource(R.string.downloading))
            Headline("MasterROM ${rom.version}")
            Body(progressText)
            ChipRow {
                Chip(stringResource(R.string.speed, sizeOf(dl.speed)))
                etaText(dl.etaSeconds)?.let { Chip(it) }
                if (dl.mirrors > 0) Chip(stringResource(R.string.servers, dl.mirrors))
            }
            Spacer(Modifier.height(4.dp))
            ButtonPair(
                secondary = stringResource(R.string.cancel), onSecondary = onCancel,
                primary = stringResource(R.string.pause), onPrimary = onPause, primaryIsGlass = true,
            )
        }
        Phase.PAUSED -> {
            ProgressRing(dl.progress, caption = stringResource(R.string.paused), active = false)
            Headline(stringResource(if (dl.error == DlError.WIFI_REQUIRED) R.string.waiting_wifi else R.string.paused))
            Body(
                when (dl.error) {
                    DlError.WIFI_REQUIRED -> stringResource(R.string.waiting_wifi_text)
                    DlError.TIME_LIMIT -> stringResource(R.string.time_limit_text)
                    else -> "MasterROM ${rom.version} · $progressText"
                },
            )
            Spacer(Modifier.height(4.dp))
            ButtonPair(
                secondary = stringResource(R.string.cancel), onSecondary = onCancel,
                primary = stringResource(R.string.resume), onPrimary = onResume,
            )
        }
        Phase.VERIFYING -> {
            ProgressRing(dl.verify, caption = "MD5")
            Headline(stringResource(R.string.verifying))
            Body(stringResource(R.string.verifying_text))
        }
        Phase.DONE -> if (install is InstallState.Preparing) {
            ProgressRing(install.progress)
            Headline(stringResource(R.string.install_preparing))
            Body(stringResource(R.string.install_preparing_text))
        } else {
            HeroIcon(HeroKind.UP_TO_DATE)
            Headline(stringResource(R.string.download_done))
            Body(stringResource(R.string.download_done_text, rom.version))
            ChipRow {
                Chip(sizeOf(rom.size))
                Chip("MD5 ✓", g.success)
                if (rom.cleanFlash) Chip(stringResource(R.string.chip_clean_flash), g.warning)
            }
            if (install is InstallState.Failed) {
                Text(
                    stringResource(
                        when (install.reason) {
                            Installer.Reason.NO_ROOT -> R.string.err_no_root
                            Installer.Reason.MOVE_FAILED -> R.string.err_move
                            Installer.Reason.SCRIPT_FAILED -> R.string.err_script
                            Installer.Reason.REBOOT_FAILED -> R.string.err_reboot
                            Installer.Reason.SYSTEM_FAILED -> R.string.err_system
                        },
                    ),
                    color = g.error,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(4.dp))
            ButtonPair(
                secondary = stringResource(R.string.delete), onSecondary = onCancel,
                primary = stringResource(R.string.install_now), onPrimary = onInstall,
            )
        }
        Phase.FAILED -> {
            HeroIcon(HeroKind.ERROR)
            Headline(stringResource(R.string.download_failed))
            Body(
                when (dl.error) {
                    DlError.NO_MIRROR -> stringResource(R.string.err_no_mirror)
                    DlError.NO_SPACE -> stringResource(R.string.err_no_space, sizeOf(missingSpace(context, dl)))
                    DlError.CHECKSUM -> stringResource(R.string.err_checksum)
                    DlError.STORAGE -> stringResource(R.string.err_storage)
                    else -> stringResource(R.string.err_network)
                },
            )
            Spacer(Modifier.height(4.dp))
            ButtonPair(
                secondary = stringResource(R.string.cancel), onSecondary = onCancel,
                primary = stringResource(R.string.try_again), onPrimary = onResume,
            )
        }
        Phase.IDLE -> Unit
    }
}

@Composable
private fun AppUpdateCard(
    release: com.masterrom.updater.data.AppRelease,
    state: SelfUpdateState,
    onUpdate: () -> Unit,
) {
    val g = LocalGlass.current
    GlassCard(padding = PaddingValues(horizontal = 22.dp, vertical = 18.dp)) {
        Text(
            stringResource(R.string.app_update_title, release.version),
            color = g.text,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
        )
        Column(Modifier.padding(top = 8.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            release.changes.filter { !it.startsWith("\u00a7") }.take(4).forEach { line ->
                Row {
                    Text("\u2022", color = g.accent, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 10.dp))
                    Text(line, color = g.subText, fontSize = 14.sp, lineHeight = 20.sp)
                }
            }
        }
        when (state) {
            is SelfUpdateState.Downloading -> Text(
                stringResource(R.string.app_update_downloading, (state.progress * 100).toInt()),
                color = g.chipText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            )
            SelfUpdateState.Installing -> Text(stringResource(R.string.app_update_installing), color = g.chipText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            else -> {
                if (state == SelfUpdateState.Failed) {
                    Text(stringResource(R.string.app_update_failed), color = g.error, fontSize = 14.sp, modifier = Modifier.padding(bottom = 10.dp))
                }
                PrimaryButton(stringResource(R.string.app_update_button), onClick = onUpdate, modifier = Modifier.fillMaxWidth(), height = 48.dp)
            }
        }
    }
}

/** Bullet list of the changelog, collapsed to the first lines with a "show all" toggle. */
@Composable
private fun ChangelogCard(lines: List<String>) {
    val g = LocalGlass.current
    var expanded by remember { mutableStateOf(false) }
    val collapsedCount = 6
    val shown = if (expanded) lines else lines.take(collapsedCount)
    GlassCard(
        modifier = Modifier.animateContentSize(),
        padding = PaddingValues(horizontal = 22.dp, vertical = 18.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            shown.forEach { line ->
                if (line.startsWith("§")) {
                    Text(line.removePrefix("§"), color = g.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                } else {
                    Row {
                        Text("•", color = g.accent, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 10.dp))
                        Text(line, color = g.subText, fontSize = 15.sp, lineHeight = 21.sp)
                    }
                }
            }
            if (lines.size > collapsedCount) {
                Text(
                    stringResource(if (expanded) R.string.show_less else R.string.show_all),
                    color = g.chipText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .clickable { expanded = !expanded },
                )
            }
        }
    }
}

@Composable
private fun ButtonPair(
    secondary: String,
    onSecondary: () -> Unit,
    primary: String,
    onPrimary: () -> Unit,
    primaryIsGlass: Boolean = false,
) {
    val g = LocalGlass.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        // cancel / delete is always red
        TintButton(secondary, g.error, onClick = onSecondary, modifier = Modifier.weight(1f))
        if (primaryIsGlass) {
            // pause is orange
            TintButton(primary, g.warning, onClick = onPrimary, modifier = Modifier.weight(1f))
        } else {
            PrimaryButton(primary, onClick = onPrimary, modifier = Modifier.weight(1f), height = 52.dp)
        }
    }
}

@Composable
internal fun SwitchRow(title: String, summary: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val g = LocalGlass.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = g.text, fontSize = 16.sp)
            Text(summary, color = g.subText, fontSize = 13.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = g.accent,
                checkedBorderColor = g.accent,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = g.subText.copy(alpha = 0.35f),
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

/** Battery level in percent and whether the phone is charging. */
private fun batteryLevel(context: android.content.Context): Pair<Int, Boolean> {
    val bm = context.getSystemService(android.os.BatteryManager::class.java)
    return bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) to bm.isCharging
}

private fun missingSpace(context: android.content.Context, dl: DlState): Long {
    val free = StatFs(DownloadManager.dir(context).path).availableBytes
    return (dl.total - dl.done + 512L * 1024 * 1024 - free).coerceAtLeast(0)
}

@Composable
private fun etaText(seconds: Long): String? = when {
    seconds < 0 -> null
    seconds < 60 -> stringResource(R.string.eta_less_min)
    seconds < 3600 -> stringResource(R.string.eta_min, (seconds / 60).toInt() + 1)
    else -> stringResource(R.string.eta_hours, (seconds / 3600).toInt(), ((seconds % 3600) / 60).toInt())
}

@Composable
private fun Headline(text: String) {
    Text(text, color = LocalGlass.current.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
}

@Composable
private fun Body(text: String) {
    Text(text, color = LocalGlass.current.subText, fontSize = 15.sp, lineHeight = 21.sp, textAlign = TextAlign.Center)
}

@Composable
private fun lastCheckedText(time: Long): String =
    if (time <= 0L) stringResource(R.string.never_checked)
    else if (System.currentTimeMillis() - time < DateUtils.MINUTE_IN_MILLIS) stringResource(R.string.last_checked, stringResource(R.string.just_now))
    else stringResource(
        R.string.last_checked,
        DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
    )
