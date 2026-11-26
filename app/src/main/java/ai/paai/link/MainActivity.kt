package ai.paai.link

import ai.paai.link.core.call.CallPhase
import ai.paai.link.ui.LinkApp
import ai.paai.link.ui.LinkTheme
import ai.paai.link.ui.LinkViewModel
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: LinkViewModel by viewModels()
    /** Pending action from a notification (answer a call, open a chat). */
    val pendingAction = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Always dark UI: light status / navigation bar icons regardless of the system theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        handle(intent)
        setContent { LinkTheme { LinkApp(vm, pendingAction) } }

        // Show over the lock screen and keep the screen on only while a call is ringing or active.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.CREATED) {
                vm.call.collect { s ->
                    val live = s.phase.isLive
                    showOverLockScreen(live, s.phase == CallPhase.INCOMING_RINGING)
                    if (live) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }
    }

    private fun showOverLockScreen(show: Boolean, wake: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(show)
            setTurnScreenOn(wake)
        } else {
            @Suppress("DEPRECATION")
            val flags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            if (show) window.addFlags(flags) else window.clearFlags(flags)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val action = intent?.action ?: return
        if (action == ACTION_ANSWER || action == ACTION_SHOW_CALL || action.startsWith(ACTION_OPEN_CHAT)) pendingAction.value = action
    }

    override fun onStart() {
        super.onStart()
        vm.c.appVisible.value = true
    }

    override fun onStop() {
        vm.c.appVisible.value = false
        // An interrupted press must never keep transmitting.
        if (vm.ptt.value.talking || vm.ptt.value.requesting) vm.releaseTalk()
        super.onStop()
    }

    companion object {
        const val ACTION_ANSWER = "ai.paai.link.ANSWER"
        const val ACTION_SHOW_CALL = "ai.paai.link.SHOW_CALL"
        const val ACTION_OPEN_CHAT = "ai.paai.link.OPEN_CHAT"
    }
}
