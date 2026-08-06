package io.github.recloudstudio.sleepymore

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.recloudstudio.sleepymore.ui.SleepyApp
import io.github.recloudstudio.sleepymore.ui.SleepyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        MiHomeMonitorService.startIfEnabled(applicationContext)
        setContent {
            SleepyTheme {
                SleepyApp()
            }
        }
    }
}
