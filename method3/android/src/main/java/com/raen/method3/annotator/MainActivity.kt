package com.raen.method3.annotator

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import com.raen.method3.annotator.ui.AnnotatorScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = Color(0xFF121216),
                    surface = Color(0xFF181820),
                    primary = Color(0xFF00E5FF),
                    secondary = Color(0xFFFFAB00)
                )
            ) {
                AnnotatorScreen()
            }
        }
    }
}
