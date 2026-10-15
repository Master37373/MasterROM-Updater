package com.masterrom.updater

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.masterrom.updater.ui.home.HomeScreen
import com.masterrom.updater.ui.theme.MasterTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MasterTheme {
                HomeScreen()
            }
        }
    }
}
