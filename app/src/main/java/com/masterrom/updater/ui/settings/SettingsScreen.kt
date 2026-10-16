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
import com.masterrom.updater.ui.home.SwitchRow
import com.masterrom.updater.ui.theme.LocalGlass

@Composable
fun SettingsScreen(vm: HomeViewModel, onBack: () -> Unit) {
    val g = LocalGlass.current
    val context = LocalContext.current
    val wifiOnly by vm.wifiOnly.collectAsStateWithLifecycle()
    val testMode by vm.testMode.collectAsStateWithLifecycle()
    val stored by vm.storedBytes.collectAsStateWithLifecycle()
    val download by vm.download.collectAsStateWithLifecycle()
    val device by vm.device.collectAsStateWithLifecycle()
    val devUnlocked by vm.devUnlocked.collectAsStateWithLifecycle()
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
            GlassCard(padding = PaddingValues(horizontal = 22.dp, vertical = 14.dp)) {
                SwitchRow(
                    title = stringResource(R.string.wifi_only),
                    summary = stringResource(R.string.wifi_only_desc),
                    checked = wifiOnly,
                    onChange = vm::setWifiOnly,
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
                InfoRow(stringResource(R.string.update_source), UpdateRepository.REPO, divider = true)
                InfoRow(stringResource(R.string.row_device), device.codename.ifEmpty { "–" }, divider = true)
            }
    }
}

private const val DEV_TAPS = 7
