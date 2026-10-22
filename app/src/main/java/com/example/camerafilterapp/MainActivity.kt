package com.example.camerafilterapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.camerafilterapp.ui.CameraScreen
import com.example.camerafilterapp.ui.theme.CameraFilterAppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CameraFilterAppTheme {
                CameraScreen()
            }
        }
    }
}
