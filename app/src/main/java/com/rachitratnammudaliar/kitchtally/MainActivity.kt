package com.rachitratnammudaliar.kitchtally

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.rachitratnammudaliar.kitchtally.ui.KitchtallyRoot
import com.rachitratnammudaliar.kitchtally.ui.KitchtallyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Debug builds accept extras to show sample data and open a screen, for screenshots.
        val demo = BuildConfig.DEBUG && intent.getBooleanExtra("kitchtally.demo", false)
        val start = if (BuildConfig.DEBUG) intent.getStringExtra("kitchtally.screen") ?: "pantry" else "pantry"
        setContent {
            KitchtallyTheme {
                KitchtallyRoot(startScreen = start, demo = demo)
            }
        }
    }
}
