package com.localaicoder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.localaicoder.ui.chat.ChatScreen
import com.localaicoder.ui.theme.LocalAICoderTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LocalAICoderTheme {
                ChatScreen()
            }
        }
    }
}
