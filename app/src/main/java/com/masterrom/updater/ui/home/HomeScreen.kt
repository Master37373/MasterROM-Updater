package com.masterrom.updater.ui.home

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.masterrom.updater.R
import com.masterrom.updater.ui.glass.Chip
import com.masterrom.updater.ui.glass.ChipRow
import com.masterrom.updater.ui.glass.GlassBackground
import com.masterrom.updater.ui.glass.GlassCard
import com.masterrom.updater.ui.glass.HeroIcon
import com.masterrom.updater.ui.glass.HeroKind
import com.masterrom.updater.ui.glass.InfoRow
import com.masterrom.updater.ui.glass.LargeTitle
import com.masterrom.updater.ui.glass.PrimaryButton
import com.masterrom.updater.ui.glass.SecondaryButton
import com.masterrom.updater.ui.glass.SectionLabel
import com.masterrom.updater.ui.theme.LocalGlass

@Composable
fun HomeScreen(vm: HomeViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val device by vm.device.collectAsStateWithLifecycle()
    val lastChecked by vm.lastChecked.collectAsStateWithLifecycle()
    val g = LocalGlass.current
    val context = LocalContext.current
    val unknown = stringResource(R.string.unknown)

    GlassBackground {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(32.dp))
            LargeTitle(stringResource(R.string.title_software_update))

            AnimatedContent(
                targetState = state,
                transitionSpec = {
                    (fadeIn(tween(350)) + scaleIn(tween(350), initialScale = 0.96f)) togetherWith fadeOut(tween(200))
                },
                contentKey = { it::class },
                label = "status",
            ) { s ->
                GlassCard {
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
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
                                    Text("MasterROM " + s.rom.version, color = g.text, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold)
                                    Body(stringResource(R.string.installed_version, device.romVersion.ifEmpty { unknown }))
                                    ChipRow {
                                        if (s.rom.size > 0) Chip(Formatter.formatShortFileSize(context, s.rom.size))
                                        if (s.rom.date.isNotEmpty()) Chip(s.rom.date)
                                        if (s.rom.cleanFlash) Chip(stringResource(R.string.chip_clean_flash), g.warning)
                                        else Chip(stringResource(R.string.chip_dirty_flash))
                                    }
                                } else if (s.kernel != null) {
                                    Headline(stringResource(R.string.kernel_update_available))
                                    Text(s.kernel.version, color = g.text, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold)
                                }
                                PrimaryButton(stringResource(R.string.download), onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth())
                                Body(stringResource(R.string.download_next_stage))
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

            SectionLabel(stringResource(R.string.section_device))
            GlassCard(padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 22.dp, vertical = 6.dp)) {
                InfoRow(stringResource(R.string.row_rom_version), device.romVersion.ifEmpty { unknown }, divider = false)
                InfoRow(stringResource(R.string.row_kernel), com.masterrom.updater.data.DeviceInfo.shortKernel(device.kernel).ifEmpty { unknown }, divider = true)
                InfoRow(stringResource(R.string.row_device), device.model + if (device.codename.isNotEmpty()) " (${device.codename})" else "", divider = true)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
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
