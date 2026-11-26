package ai.paai.link.ui

import ai.paai.link.R
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: LinkViewModel, onBack: () -> Unit) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val online by vm.online.collectAsStateWithLifecycle()
    val fingerprint by vm.fingerprint.collectAsStateWithLifecycle()
    var name by remember(s.displayName) { mutableStateOf(s.displayName) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = LinkColors.Background),
        )
        LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SectionCard("Profile") {
                    OutlinedTextField(name, { name = it.take(40) }, label = { Text("Name shown to others") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = name.isNotBlank() && name != s.displayName, onClick = { vm.updateSettings { it.copy(displayName = name) } }) { Text("Save") }
                    }
                    if (online) Hint("The new name is announced the next time you go online.")
                    Hint("Security fingerprint: ${fingerprint ?: "…"}")
                }
            }
            item {
                SectionCard("Relaying") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Relay for paired devices")
                            Hint("Forward encrypted calls and messages between paired phones that cannot reach each other. Relays cannot read them.")
                        }
                        Switch(s.relayEnabled, { on -> vm.updateSettings { it.copy(relayEnabled = on) } })
                    }
                    SettingSlider(
                        label = { "Pause relaying below $it% battery (unless charging)" },
                        value = s.lowBatteryRelayCutoff, range = 0..50, steps = 9,
                        onCommit = { v -> vm.updateSettings { it.copy(lowBatteryRelayCutoff = v) } },
                    )
                }
            }
            item {
                SectionCard("Groups") {
                    SettingSlider(
                        label = { "Maximum group size: $it (including you)" },
                        value = s.maxGroupMembers, range = 2..8, steps = 5,
                        onCommit = { v -> vm.updateSettings { it.copy(maxGroupMembers = v) } },
                    )
                    Hint("The host's phone relays every member's audio. Larger groups need more battery and Wi-Fi capacity; test the size you need.")
                }
            }
            item {
                SectionCard("Messages") {
                    SettingSlider(
                        label = { if (it == 0) "Keep messages forever" else "Delete messages after $it days" },
                        value = s.retentionDays, range = 0..365, steps = 0,
                        onCommit = { v -> vm.updateSettings { it.copy(retentionDays = v) } },
                    )
                    OutlinedButton(onClick = vm::clearCallLog) { Text("Clear call history") }
                }
            }
            item {
                SectionCard("Privacy") {
                    Hint(
                        "SRCS works without internet, servers or accounts. Voice and messages travel directly between phones, " +
                            "end-to-end encrypted. Your identity key is stored only on this phone (protected by Android Keystore) and is " +
                            "excluded from backups. Nothing is sent to any cloud service. Diagnostics stay on this phone.",
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** Slider that shows the value while dragging and saves once when released. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingSlider(label: (Int) -> String, value: Int, range: IntRange, steps: Int, onCommit: (Int) -> Unit) {
    val state = remember(value) { SliderState(value.toFloat(), steps, range.first.toFloat()..range.last.toFloat()) }
    Text(label(state.value.roundToInt()))
    Slider(
        state = state,
        onValueChange = { state.value = it },
        onValueChangeFinished = { onCommit(state.value.roundToInt()) },
    )
}

@Composable
fun OnboardingScreen(vm: LinkViewModel, modifier: Modifier = Modifier) {
    var name by remember { mutableStateOf(android.os.Build.MODEL ?: "") }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(32.dp))
        Image(painterResource(R.drawable.ic_launcher), contentDescription = null, modifier = Modifier.size(120.dp))
        Text("SRCS", fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Text("Short Range Communication System", style = MaterialTheme.typography.titleMedium, color = LinkColors.Accent)
        Text("Talk to nearby phones without mobile network, internet or Wi-Fi router.", style = MaterialTheme.typography.bodyLarge)
        SectionCard("How it works") {
            Hint("1. Both phones open SRCS and go online.")
            Hint("2. Scan, tap Connect, accept the Wi-Fi Direct invitation on the other phone.")
            Hint("3. Check that both phones show the same 6-digit code, then confirm. You only pair once.")
            Hint("4. Call, use the walkie-talkie, or send messages. Everything is end-to-end encrypted.")
            Hint("Range depends on the phones and surroundings — often tens of metres in open space, less through walls. A third phone in between can relay.")
        }
        OutlinedTextField(name, { name = it.take(40) }, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(
            enabled = name.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            onClick = { vm.updateSettings { it.copy(displayName = name.trim(), onboarded = true) } },
        ) { Text("Get started") }
    }
}
