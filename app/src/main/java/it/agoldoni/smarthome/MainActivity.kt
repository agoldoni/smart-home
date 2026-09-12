package it.agoldoni.smarthome

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import it.agoldoni.smarthome.ui.SmartHomeApp
import it.agoldoni.smarthome.ui.theme.SmartHomeTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            SmartHomeTheme {
                SmartHomeApp()
            }
        }
    }
}
