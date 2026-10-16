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
    val g = LocalGlass.current
    val context = LocalContext.current
    val sections = remember { Changelog.ofApp(context) }
    CollapsingScreen(
        title = stringResource(R.string.app_changelog),
        navigation = {
            GlassIconButton(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.back), onClick = onBack)
        },
    ) {
        sections.forEach { section ->
            val installed = section.title.trim() == BuildConfig.VERSION_NAME
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
