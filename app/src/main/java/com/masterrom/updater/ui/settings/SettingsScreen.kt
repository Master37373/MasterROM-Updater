package com.masterrom.updater.ui.settings

import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.masterrom.updater.ui.glass.CollapsingScreen
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.masterrom.updater.BuildConfig
import com.masterrom.updater.R
import com.masterrom.updater.data.UpdateRepository
import com.masterrom.updater.download.Phase
import com.masterrom.updater.ui.glass.GlassBackground
import com.masterrom.updater.ui.glass.GlassCard
import com.masterrom.updater.ui.glass.GlassIconButton
import com.masterrom.updater.ui.glass.InfoRow
import com.masterrom.updater.ui.glass.LargeTitle
import com.masterrom.updater.ui.glass.SecondaryButton
import com.masterrom.updater.ui.glass.SectionLabel
import com.masterrom.updater.ui.home.HomeViewModel
import com.masterrom.updater.ui.theme.LocalGlass

@Composable
fun SettingsScreen(vm: HomeViewModel, onBack: () -> Unit, onOpenChangelog: () -> Unit = {}, onOpenHistory: () -> Unit = {}) {
    val g = LocalGlass.current
    val context = LocalContext.current
    val wifiOnly by vm.wifiOnly.collectAsStateWithLifecycle()
    val autoCheck by vm.autoCheck.collectAsStateWithLifecycle()
    val testMode by vm.testMode.collectAsStateWithLifecycle()
    val stored by vm.storedBytes.collectAsStateWithLifecycle()
    val download by vm.download.collectAsStateWithLifecycle()
    val device by vm.device.collectAsStateWithLifecycle()
    val devUnlocked by vm.devUnlocked.collectAsStateWithLifecycle()
    val autoDownload by vm.autoDownload.collectAsStateWithLifecycle()
    val nightMinutes by vm.nightMinutes.collectAsStateWithLifecycle()
    val localZip by vm.localZip.collectAsStateWithLifecycle()
    var confirmLocal by remember { mutableStateOf(false) }
    val pickZip = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) vm.pickLocalZip(uri) }
    var taps by remember { mutableIntStateOf(0) }
    var toast by remember { mutableStateOf<Toast?>(null) }
    val busy = download.phase == Phase.RUNNING || download.phase == Phase.PREPARING || download.phase == Phase.VERIFYING

    LaunchedEffect(download.phase) { vm.refreshStorage() }

    CollapsingScreen(
        title = stringResource(R.string.section_settings),
        navigation = {
            GlassIconButton(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.back), onClick = onBack)
        },
    ) {

            SectionLabel(stringResource(R.string.settings_download))
            GlassCard(padding = PaddingValues(horizontal = 22.dp, vertical = 4.dp)) {
                SwitchRow(
                    title = stringResource(R.string.auto_check),
                    summary = stringResource(R.string.auto_check_desc),
                    checked = autoCheck,
                    onChange = vm::setAutoCheck,
                    divider = false,
                )
                SwitchRow(
                    title = stringResource(R.string.auto_download),
                    summary = stringResource(R.string.auto_download_desc),
                    checked = autoDownload,
                    onChange = vm::setAutoDownload,
                )
                // shown the other way round: "use mobile data" is easier to read than "Wi-Fi only"
                SwitchRow(
                    title = stringResource(R.string.mobile_data),
                    summary = stringResource(R.string.mobile_data_desc),
                    checked = !wifiOnly,
                    onChange = { vm.setWifiOnly(!it) },
                )
                val nightLabel = remember(nightMinutes) {
                    val cal = java.util.Calendar.getInstance().apply {
                        set(java.util.Calendar.HOUR_OF_DAY, nightMinutes / 60)
                        set(java.util.Calendar.MINUTE, nightMinutes % 60)
                    }
                    android.text.format.DateFormat.getTimeFormat(context).format(cal.time)
                }
                InfoRow(
                    stringResource(R.string.night_time),
                    nightLabel,
                    divider = true,
                    onClick = {
                        android.app.TimePickerDialog(
                            context,
                            { _, h, m -> vm.setNightMinutes(h * 60 + m) },
                            nightMinutes / 60,
                            nightMinutes % 60,
                            android.text.format.DateFormat.is24HourFormat(context),
                        ).show()
                    },
                )
            }

            SectionLabel(stringResource(R.string.settings_storage))
            GlassCard(padding = PaddingValues(horizontal = 22.dp, vertical = 14.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.stored_updates), color = g.text, fontSize = 16.sp)
                        Text(
                            if (stored > 0) Formatter.formatShortFileSize(context, stored) else stringResource(R.string.stored_none),
                            color = g.subText,
                            fontSize = 13.sp,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    if (stored > 0 && !busy) {
                        SecondaryButton(stringResource(R.string.delete), onClick = vm::deleteDownloads, height = 40.dp)
                    }
                }
            }

            SectionLabel(stringResource(R.string.settings_manual))
            LocalZipCard(
                state = localZip,
                onPick = { pickZip.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
                onInstall = { confirmLocal = true },
                onClear = vm::clearLocalZip,
            )

            AnimatedVisibility(
                visible = devUnlocked,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SectionLabel(stringResource(R.string.settings_developer))
                    GlassCard(padding = PaddingValues(horizontal = 22.dp, vertical = 14.dp)) {
                        SwitchRow(
                            title = stringResource(R.string.chip_test),
                            summary = stringResource(R.string.test_mode_desc),
                            checked = testMode,
                            onChange = vm::setTestMode,
                            divider = false,
                        )
                        Spacer(Modifier.height(12.dp))
                        SecondaryButton(
                            stringResource(R.string.dev_hide),
                            onClick = {
                                vm.setDevUnlocked(false)
                                taps = 0
                            },
                            modifier = Modifier.fillMaxWidth(),
                            height = 44.dp,
                        )
                    }
                }
            }

            SectionLabel(stringResource(R.string.settings_about))
            GlassCard(padding = PaddingValues(horizontal = 22.dp, vertical = 6.dp)) {
                InfoRow(
                    stringResource(R.string.app_version),
                    BuildConfig.VERSION_NAME,
                    divider = false,
                    onClick = {
                        if (devUnlocked) {
                            toast?.cancel()
                            toast = Toast.makeText(context, R.string.dev_already, Toast.LENGTH_SHORT).also { it.show() }
                        } else {
                            taps++
                            val left = DEV_TAPS - taps
                            toast?.cancel()
                            if (left <= 0) {
                                vm.setDevUnlocked(true)
                                toast = Toast.makeText(context, R.string.dev_unlocked, Toast.LENGTH_SHORT).also { it.show() }
                            } else if (left <= 4) {
                                toast = Toast.makeText(
                                    context,
                                    context.resources.getQuantityString(R.plurals.dev_taps_left, left, left),
                                    Toast.LENGTH_SHORT,
                                ).also { it.show() }
                            }
                        }
                    },
                )
                InfoRow(stringResource(R.string.rom_history), "›", divider = true, onClick = onOpenHistory)
                InfoRow(stringResource(R.string.app_changelog), "›", divider = true, onClick = onOpenChangelog)
                InfoRow(stringResource(R.string.update_source), UpdateRepository.REPO, divider = true)
                InfoRow(stringResource(R.string.row_device), device.codename.ifEmpty { "–" }, divider = true)
            }
    }

    if (confirmLocal) {
        val ready = localZip as? com.masterrom.updater.ui.home.LocalZipState.Ready
        val name = ready?.name.orEmpty()
        val extra = listOfNotNull(ready?.check?.let { localWarning(it) }, ready?.check?.let { localAgeText(it) }
            ?.takeIf { ready?.check?.age == com.masterrom.updater.install.LocalZip.Age.OLDER })
        val bm = context.getSystemService(android.os.BatteryManager::class.java)
        val level = remember { bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) }
        val lowBattery = level < 30 && !bm.isCharging
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmLocal = false },
            title = { Text(stringResource(R.string.local_install_title, name)) },
            text = {
                Text(
                    if (lowBattery) stringResource(R.string.install_battery_low, level)
                    else (listOf(stringResource(R.string.local_install_text)) + extra).joinToString("\n\n"),
                )
            },
            confirmButton = {
                if (!lowBattery) {
                    androidx.compose.material3.TextButton(onClick = { confirmLocal = false; vm.installLocalZip() }) {
                        Text(stringResource(R.string.install_confirm))
                    }
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmLocal = false }) { Text(stringResource(R.string.cancel_no)) }
            },
            shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
        )
    }
}

/** "Install a ZIP": pick a file, it is copied and checked, then installed like an update. */
@Composable
private fun LocalZipCard(
    state: com.masterrom.updater.ui.home.LocalZipState,
    onPick: () -> Unit,
    onInstall: () -> Unit,
    onClear: () -> Unit,
) {
    val g = LocalGlass.current
    val context = LocalContext.current
    GlassCard(padding = PaddingValues(horizontal = 22.dp, vertical = 16.dp)) {
        when (state) {
            com.masterrom.updater.ui.home.LocalZipState.Idle -> {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null,
                            onClick = onPick,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.local_title), color = g.text, fontSize = 16.sp)
                        Text(stringResource(R.string.local_desc), color = g.subText, fontSize = 13.sp, lineHeight = 18.sp)
                    }
                    Text("›", color = g.subText, fontSize = 22.sp, modifier = Modifier.padding(start = 12.dp))
                }
            }
            is com.masterrom.updater.ui.home.LocalZipState.Copying -> {
                Text(state.name, color = g.text, fontSize = 16.sp, maxLines = 2)
                Text(
                    stringResource(R.string.local_copying, (state.progress * 100).toInt()),
                    color = g.chipText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(10.dp))
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = g.accent,
                    trackColor = g.accent.copy(alpha = 0.18f),
                )
                Spacer(Modifier.height(12.dp))
                com.masterrom.updater.ui.glass.TintButton(stringResource(R.string.cancel), g.error, onClick = onClear, modifier = Modifier.fillMaxWidth(), height = 44.dp)
            }
            is com.masterrom.updater.ui.home.LocalZipState.Checking -> {
                Text(state.name, color = g.text, fontSize = 16.sp, maxLines = 2)
                Text(
                    stringResource(R.string.local_checking, (state.progress * 100).toInt()),
                    color = g.chipText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(10.dp))
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = g.accent,
                    trackColor = g.accent.copy(alpha = 0.18f),
                )
            }
            is com.masterrom.updater.ui.home.LocalZipState.Ready -> {
                val check = state.check
                Text(state.name, color = g.text, fontSize = 16.sp, maxLines = 2)
                Text(Formatter.formatShortFileSize(context, state.size), color = g.subText, fontSize = 13.sp)
                Spacer(Modifier.height(8.dp))
                com.masterrom.updater.ui.glass.ChipRow {
                    when (check.trust) {
                        com.masterrom.updater.install.LocalZip.Trust.OFFICIAL ->
                            com.masterrom.updater.ui.glass.Chip(stringResource(R.string.local_official), g.success)
                        com.masterrom.updater.install.LocalZip.Trust.RESIGNED ->
                            com.masterrom.updater.ui.glass.Chip(stringResource(R.string.local_resigned), g.error)
                        com.masterrom.updater.install.LocalZip.Trust.UNSIGNED ->
                            com.masterrom.updater.ui.glass.Chip(stringResource(R.string.local_unsigned), g.error)
                    }
                    localVersionText(check)?.let { com.masterrom.updater.ui.glass.Chip(it) }
                }
                localWarning(check)?.let { w ->
                    Text(w, color = g.error, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 8.dp))
                }
                localAgeText(check)?.let { a ->
                    Text(
                        a,
                        color = if (check.age == com.masterrom.updater.install.LocalZip.Age.OLDER) g.warning else g.subText,
                        fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    com.masterrom.updater.ui.glass.TintButton(stringResource(R.string.cancel), g.error, onClick = onClear, modifier = Modifier.weight(1f), height = 46.dp)
                    if (check.trust == com.masterrom.updater.install.LocalZip.Trust.OFFICIAL) {
                        com.masterrom.updater.ui.glass.PrimaryButton(stringResource(R.string.local_install), onClick = onInstall, modifier = Modifier.weight(1f), height = 46.dp)
                    } else {
                        com.masterrom.updater.ui.glass.TintButton(stringResource(R.string.local_install_anyway), g.warning, onClick = onInstall, modifier = Modifier.weight(1f), height = 46.dp)
                    }
                }
            }
            is com.masterrom.updater.ui.home.LocalZipState.Preparing -> {
                Text(
                    stringResource(R.string.install_preparing) + if (state.progress >= 0f) " ${(state.progress * 100).toInt()}%" else "",
                    color = g.chipText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                )
                Text(stringResource(R.string.install_preparing_text), color = g.subText, fontSize = 13.sp, lineHeight = 18.sp)
            }
            is com.masterrom.updater.ui.home.LocalZipState.Failed -> {
                Text(
                    stringResource(
                        when (state.reason) {
                            com.masterrom.updater.install.LocalZip.Reason.NO_SPACE -> R.string.local_err_space
                            com.masterrom.updater.install.LocalZip.Reason.NOT_FLASHABLE -> R.string.local_err_flashable
                            com.masterrom.updater.install.LocalZip.Reason.READ_FAILED -> R.string.local_err_read
                            com.masterrom.updater.install.LocalZip.Reason.WRONG_DEVICE -> R.string.local_err_device
                            null -> R.string.err_system
                        },
                    ),
                    color = g.error, fontSize = 14.sp, lineHeight = 20.sp,
                )
                Spacer(Modifier.height(12.dp))
                SecondaryButton(stringResource(R.string.local_pick), onClick = { onClear(); onPick() }, modifier = Modifier.fillMaxWidth(), height = 44.dp)
            }
        }
    }
}

private const val DEV_TAPS = 7

/** "MasterROM 1.0.2" / "ArtisanKRNL 4.0.1 · mr3.1" for the chip, null when unknown. */
@Composable
private fun localVersionText(c: com.masterrom.updater.install.LocalZip.Check): String? {
    val v = c.version ?: return null
    return when (c.kind) {
        com.masterrom.updater.install.LocalZip.Kind.ROM -> "MasterROM $v"
        com.masterrom.updater.install.LocalZip.Kind.KERNEL -> com.masterrom.updater.data.KernelVersion.label(v) ?: v
        com.masterrom.updater.install.LocalZip.Kind.OTHER -> null
    }
}

/** Warning for a ZIP that is not signed by MasterROM, null for an official one. */
@Composable
private fun localWarning(c: com.masterrom.updater.install.LocalZip.Check): String? = when (c.trust) {
    com.masterrom.updater.install.LocalZip.Trust.OFFICIAL -> null
    com.masterrom.updater.install.LocalZip.Trust.RESIGNED -> stringResource(R.string.local_resigned_warning)
    com.masterrom.updater.install.LocalZip.Trust.UNSIGNED -> stringResource(R.string.local_unsigned_warning)
}

/** Newer / same / older than the installed version. */
@Composable
private fun localAgeText(c: com.masterrom.updater.install.LocalZip.Check): String? = when (c.age) {
    com.masterrom.updater.install.LocalZip.Age.NEWER -> stringResource(R.string.local_age_newer, c.installed)
    com.masterrom.updater.install.LocalZip.Age.SAME -> stringResource(R.string.local_age_same)
    com.masterrom.updater.install.LocalZip.Age.OLDER -> stringResource(
        if (c.kind == com.masterrom.updater.install.LocalZip.Kind.ROM) R.string.local_age_older_rom else R.string.local_age_older,
        c.installed,
    )
    com.masterrom.updater.install.LocalZip.Age.UNKNOWN -> null
}

/** Settings row with a switch; tapping the whole row toggles it. A divider separates it from the row above. */
@Composable
private fun SwitchRow(title: String, summary: String, checked: Boolean, onChange: (Boolean) -> Unit, divider: Boolean = true) {
    val g = LocalGlass.current
    Column {
        if (divider) androidx.compose.foundation.layout.Box(
            Modifier.fillMaxWidth().height(1.dp).background(g.divider),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                ) { onChange(!checked) }
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = g.text, fontSize = 16.sp)
                Text(summary, color = g.subText, fontSize = 13.sp, lineHeight = 18.sp)
            }
            Spacer(Modifier.width(12.dp))
            androidx.compose.material3.Switch(
                checked = checked,
                onCheckedChange = onChange,
                colors = androidx.compose.material3.SwitchDefaults.colors(
                    checkedThumbColor = androidx.compose.ui.graphics.Color.White,
                    checkedTrackColor = g.accent,
                    checkedBorderColor = g.accent,
                    uncheckedThumbColor = androidx.compose.ui.graphics.Color.White,
                    uncheckedTrackColor = g.subText.copy(alpha = 0.35f),
                    uncheckedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
            )
        }
    }
}
