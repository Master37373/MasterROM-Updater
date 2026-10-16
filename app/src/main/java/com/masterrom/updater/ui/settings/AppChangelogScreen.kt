package com.masterrom.updater.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.masterrom.updater.BuildConfig
import com.masterrom.updater.R
import com.masterrom.updater.data.Changelog
import com.masterrom.updater.ui.glass.Chip
import com.masterrom.updater.ui.glass.CollapsingScreen
import com.masterrom.updater.ui.glass.GlassCard
import com.masterrom.updater.ui.glass.GlassIconButton
import com.masterrom.updater.ui.theme.LocalGlass

/** All versions of the updater with their changes; the installed one is marked. */
@Composable
fun AppChangelogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val sections = remember { Changelog.ofApp(context) }
    VersionListScreen(stringResource(R.string.app_changelog), sections, BuildConfig.VERSION_NAME, onBack)
}

/** All MasterROM versions with their changes (CHANGELOG.md of the ROM repo); the installed one is marked. */
@Composable
fun RomHistoryScreen(onBack: () -> Unit) {
    val g = LocalGlass.current
    var sections by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<List<Changelog.Section>?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) { sections = Changelog.allRom() }
    val installed = androidx.compose.runtime.remember { com.masterrom.updater.data.DeviceInfo.romVersion }
    val list = sections
    VersionListScreen(stringResource(R.string.rom_history), list.orEmpty(), installed, onBack) {
        if (list == null || list.isEmpty()) {
            GlassCard(padding = PaddingValues(horizontal = 22.dp, vertical = 18.dp)) {
                Text(
                    stringResource(if (list == null) R.string.loading else R.string.error_network),
                    color = g.subText,
                    fontSize = 15.sp,
                )
            }
        }
    }
}

@Composable
private fun VersionListScreen(
    title: String,
    sections: List<Changelog.Section>,
    installedVersion: String,
    onBack: () -> Unit,
    header: @Composable () -> Unit = {},
) {
    val g = LocalGlass.current
    CollapsingScreen(
        title = title,
        navigation = {
            GlassIconButton(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.back), onClick = onBack)
        },
    ) {
        header()
        sections.forEach { section ->
            val installed = section.title.trim() == installedVersion
            GlassCard(padding = PaddingValues(horizontal = 22.dp, vertical = 18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.version_x, section.title),
                        color = g.text,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    if (installed) {
                        Spacer(Modifier.width(10.dp))
                        Chip(stringResource(R.string.installed_chip), g.success)
                    }
                }
                Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    section.lines.forEach { line ->
                        if (line.startsWith("\u00a7")) {
                            Text(line.removePrefix("\u00a7"), color = g.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        } else {
                            Row {
                                Text("\u2022", color = g.accent, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 10.dp))
                                Text(line, color = g.subText, fontSize = 15.sp, lineHeight = 21.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
