package io.nekohasekai.sfa.compose.screen.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.database.Settings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SsidModeScreen(navController: NavController) {
    OverrideTopBar {
        TopAppBar(
            title = { Text(stringResource(R.string.ssid_mode_title)) },
            navigationIcon = {
                IconButton(onClick = { navController.navigateUp() }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.content_description_back),
                    )
                }
            },
        )
    }

    var enabled by remember { mutableStateOf(Settings.ssidModeEnabled) }
    var rules by remember { mutableStateOf(parseRules(Settings.ssidModeList)) }
    var ssid by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("direct") }
    var modeMenu by remember { mutableStateOf(false) }

    fun persist() {
        Settings.ssidModeEnabled = enabled
        Settings.ssidModeList = rules.entries.joinToString("\n") { "${it.key}=${it.value}" }
        reloadIfRunning()
    }

    val scaffoldPadding = LocalScaffoldPadding.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
            .padding(
                top = scaffoldPadding.calculateTopPadding() + 8.dp,
                bottom = scaffoldPadding.calculateBottomPadding() + 8.dp,
            ),
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.ssid_mode_enabled)) },
                supportingContent = { Text(stringResource(R.string.ssid_mode_desc)) },
                trailingContent = {
                    Switch(
                        checked = enabled,
                        onCheckedChange = {
                            enabled = it
                            persist()
                        },
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Column {
                if (rules.isEmpty()) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.ssid_mode_empty)) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
                rules.forEach { (name, value) ->
                    ListItem(
                        headlineContent = { Text(name) },
                        supportingContent = { Text(modeLabel(value)) },
                        trailingContent = {
                            IconButton(onClick = {
                                rules = rules - name
                                persist()
                            }) {
                                Icon(
                                    imageVector = Icons.Outlined.Delete,
                                    contentDescription = stringResource(R.string.ssid_mode_delete),
                                )
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = ssid,
                    onValueChange = { ssid = it },
                    label = { Text(stringResource(R.string.ssid_mode_ssid)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box {
                        TextButton(onClick = { modeMenu = true }) {
                            Text(modeLabel(mode))
                        }
                        DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                            listOf("rule", "global", "direct").forEach {
                                DropdownMenuItem(
                                    text = { Text(modeLabel(it)) },
                                    onClick = {
                                        mode = it
                                        modeMenu = false
                                    },
                                )
                            }
                        }
                    }
                    Button(
                        onClick = {
                            val key = ssid.trim()
                            if (key.isEmpty()) return@Button
                            rules = rules + (key to mode)
                            ssid = ""
                            persist()
                        },
                    ) {
                        Text(stringResource(R.string.ssid_mode_add))
                    }
                }
            }
        }

        Text(
            text = stringResource(R.string.ssid_mode_saved),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
    }
}

private fun parseRules(source: String): Map<String, String> {
    val result = linkedMapOf<String, String>()
    for (line in source.lineSequence()) {
        val index = line.indexOf('=')
        if (index <= 0) continue
        result[line.substring(0, index).trim()] = line.substring(index + 1).trim().lowercase()
    }
    return result
}

@Composable
private fun modeLabel(mode: String): String = when (mode) {
    "direct" -> stringResource(R.string.ssid_mode_direct)
    "global" -> stringResource(R.string.ssid_mode_global)
    else -> stringResource(R.string.ssid_mode_rule)
}

private fun reloadIfRunning() {
    runCatching { Libbox.newStandaloneCommandClient().serviceReload() }
}
