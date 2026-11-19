package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.ui.WebPortalScreen
import com.example.ui.WebPortalViewModel
import com.example.ui.theme.WebPortalTheme

class MainActivity : ComponentActivity() {

    private val viewModel: WebPortalViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WebPortalTheme {
                WebPortalScreen(viewModel = viewModel)
            }
        }
    }
}
