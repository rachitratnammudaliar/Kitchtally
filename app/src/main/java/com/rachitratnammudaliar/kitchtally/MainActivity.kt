package com.rachitratnammudaliar.kitchtally

import android.content.Intent
import android.os.Bundle
import androidx.compose.runtime.mutableStateOf
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.rachitratnammudaliar.kitchtally.ui.KitchtallyRoot
import com.rachitratnammudaliar.kitchtally.ui.KitchtallyTheme

class MainActivity : ComponentActivity() {
    /** Bumped when the widget asks for the shopping list while the app is already open. */
    private val openRequest = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Debug builds accept extras to show sample data and open a screen, for screenshots.
        val demo = BuildConfig.DEBUG && intent.getBooleanExtra("kitchtally.demo", false)
        val start = if (BuildConfig.DEBUG) intent.getStringExtra("kitchtally.screen") ?: openTab(intent) else openTab(intent)
        setContent {
            KitchtallyTheme {
                KitchtallyRoot(startScreen = start, demo = demo, openRequest = openRequest.value, onOpened = { openRequest.value = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openRequest.value = openTab(intent)
    }

    /** The widget opens the shopping list; anything else opens the pantry. */
    private fun openTab(intent: Intent?): String = if (intent?.getStringExtra(EXTRA_OPEN) == "shopping") "shopping" else "pantry"

    companion object {
        const val EXTRA_OPEN = "kitchtally.open"
    }
}
