package ai.paai.link.ui

import ai.paai.link.MainActivity
import ai.paai.link.core.call.CallPhase
import ai.paai.link.core.util.DeviceId
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.MutableStateFlow

object Perms {
    val discovery: Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    val notifications: Array<String> = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()
    val microphone: Array<String> = arrayOf(Manifest.permission.RECORD_AUDIO)
    /** Optional: lets the app list Bluetooth headsets on Android 12+. */
    val bluetooth: Array<String> = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_CONNECT) else emptyArray()
}

/** Explains why a permission is needed before asking, and how to fix a permanent denial. */
class PermissionGate(
    private val check: (String) -> Boolean,
    private val ask: (Array<String>, String, () -> Unit, () -> Unit) -> Unit,
) {
    private val askedOptional = HashSet<String>()

    fun has(perms: Array<String>) = perms.all(check)

    /** Run [onGranted] once [required] are granted. [optional] permissions are requested but not required. */
    fun withPermissions(required: Array<String>, reason: String, optional: Array<String> = emptyArray(), onGranted: () -> Unit) {
        val missingOptional = optional.filterNot(check).filterNot { it in askedOptional }
        if (required.all(check) && missingOptional.isEmpty()) {
            onGranted()
            return
        }
        // Optional permissions are asked for once; the action proceeds whatever the answer.
        askedOptional += missingOptional
        val proceedIfAllowed = { if (required.all(check)) onGranted() }
        // "Not now" on an optional-only request still runs the action.
        ask(required.filterNot(check).toTypedArray() + missingOptional, reason, proceedIfAllowed, proceedIfAllowed)
    }
}

@Composable
fun rememberPermissionGate(vm: LinkViewModel): PermissionGate {
    val context = LocalContext.current
    var rationale by remember { mutableStateOf<Rationale?>(null) }
    var denied by remember { mutableStateOf<String?>(null) }
    var pendingThen by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingReason by remember { mutableStateOf("") }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val then = pendingThen
        pendingThen = null
        val requiredDenied = result.filterValues { !it }.keys.filterNot { it in Perms.bluetooth || it in Perms.notifications }
        if (requiredDenied.isNotEmpty()) denied = pendingReason
        then?.invoke()
    }
    rationale?.let { (perms, reason, then, onDecline) ->
        AlertDialog(
            onDismissRequest = { rationale = null; onDecline() },
            title = { Text("Permission needed") },
            text = { Text(reason) },
            confirmButton = {
                TextButton(onClick = {
                    rationale = null
                    pendingThen = then
                    pendingReason = reason
                    launcher.launch(perms)
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { rationale = null; onDecline() }) { Text("Not now") } },
        )
    }
    denied?.let { reason ->
        AlertDialog(
            onDismissRequest = { denied = null },
            title = { Text("Permission denied") },
            text = { Text("$reason\n\nYou can allow it in system settings.") },
            confirmButton = {
                TextButton(onClick = {
                    denied = null
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }) { Text("Open settings") }
            },
            dismissButton = { TextButton(onClick = { denied = null }) { Text("Close") } },
        )
    }
    return remember { PermissionGate(check = vm.c::hasPermission, ask = { p, r, then, decline -> rationale = Rationale(p, r, then, decline) }) }
}

private data class Rationale(val perms: Array<String>, val reason: String, val then: () -> Unit, val onDecline: () -> Unit)

object Reasons {
    const val DISCOVERY = "To find nearby phones over Wi-Fi Direct, Android requires the Nearby devices permission " +
        "(Location on Android 12 and older). SRCS does not use or store your location."
    const val MIC = "Calls, group calls and push-to-talk need the microphone. Audio goes only to the devices in the call, encrypted. " +
        "Android may also ask about nearby devices: that lets SRCS send call audio to a Bluetooth headset (optional, asked once)."
    const val NOTIFY = "Notifications show incoming calls, messages and the online status while the app is in the background."
}

@Composable
fun LinkApp(vm: LinkViewModel, pendingAction: MutableStateFlow<String?>) {
    val nav = rememberNavController()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val call by vm.call.collectAsStateWithLifecycle()
    val links by vm.links.collectAsStateWithLifecycle()
    val action by pendingAction.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val gate = rememberPermissionGate(vm)

    LaunchedEffect(Unit) { vm.toasts.collect { snackbar.showSnackbar(it) } }

    // Restore the online state the user left the app in (e.g. after Android stopped the process).
    LaunchedEffect(settings.wasOnline) {
        if (settings.wasOnline && !vm.online.value && !vm.transitioning.value) vm.goOnline()
    }

    // Notification actions.
    LaunchedEffect(action, call.phase) {
        val a = action ?: return@LaunchedEffect
        when {
            a == MainActivity.ACTION_ANSWER && call.phase == CallPhase.INCOMING_RINGING -> {
                pendingAction.value = null
                gate.withPermissions(Perms.microphone, Reasons.MIC, Perms.bluetooth) { vm.accept() }
            }
            a.startsWith(MainActivity.ACTION_OPEN_CHAT) -> {
                pendingAction.value = null
                a.substringAfter(':', "").takeIf { it.length == 32 }?.let { nav.navigate("chat/$it") }
            }
            a == MainActivity.ACTION_SHOW_CALL -> pendingAction.value = null
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, containerColor = LinkColors.Background) { pad ->
        if (!settings.onboarded) {
            OnboardingScreen(vm, Modifier.padding(pad))
            return@Scaffold
        }
        NavHost(nav, startDestination = "home", modifier = Modifier.padding(pad).fillMaxSize()) {
            composable("home") { HomeScreen(vm, gate, onNavigate = { nav.navigate(it) }) }
            composable("chats") { ConversationsScreen(vm, onOpen = { nav.navigate("chat/${it.hex}") }, onBack = { nav.popBackStack() }) }
            composable("chat/{id}") { entry ->
                val id = entry.arguments?.getString("id")?.let { runCatching { DeviceId.fromHex(it) }.getOrNull() }
                if (id != null) ChatScreen(vm, id, onBack = { nav.popBackStack() })
            }
            composable("group") { GroupScreen(vm, gate, onBack = { nav.popBackStack() }, onChat = { nav.navigate("chat/${it.hex}") }) }
            composable("diagnostics") { DiagnosticsScreen(vm, onBack = { nav.popBackStack() }) }
            composable("settings") { SettingsScreen(vm, onBack = { nav.popBackStack() }) }
        }

        // Pairing: compare the code shown on both phones.
        links.firstOrNull { !it.trusted && !it.localConfirmed }?.let { l ->
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Pair with ${l.remoteName}?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Check that the other phone shows the same code:")
                        Text(l.sas, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = LinkColors.Accent, style = MaterialTheme.typography.headlineLarge)
                        Hint("If the codes differ, someone may be intercepting the connection. Tap Cancel.")
                    }
                },
                confirmButton = { TextButton(onClick = { vm.confirmPairing(l.linkId, true) }) { Text("Codes match") } },
                dismissButton = { TextButton(onClick = { vm.confirmPairing(l.linkId, false) }) { Text("Cancel") } },
            )
        }
        links.firstOrNull { !it.trusted && it.localConfirmed && !it.remoteConfirmed }?.let { l ->
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Waiting for ${l.remoteName}") },
                text = { Text("Confirm code ${l.sas} on the other phone too.") },
                confirmButton = {},
                dismissButton = { TextButton(onClick = { vm.disconnect(l.remoteId) }) { Text("Cancel") } },
            )
        }

        if (call.phase != CallPhase.IDLE) CallScreen(vm, gate)
    }
}
