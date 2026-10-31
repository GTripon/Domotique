package com.example.domotique1

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.addCallback
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.example.domotique1.data.VoletsRepository
import com.example.domotique1.ui.SettingsScreen
import com.example.domotique1.ui.VoletsScreen
import com.example.domotique1.ui.theme.Domotique1Theme
import android.os.Build
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager

class MainActivity : ComponentActivity() {

    private val localNetworkPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) {
                // Local network permission granted
            } else {
                // Permission denied
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val repository = (application as DomotiqueApp).repository

        // Request local network permission before starting network operations
        if (Build.VERSION.SDK_INT >= 37) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_LOCAL_NETWORK
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                localNetworkPermissionLauncher.launch(
                    Manifest.permission.ACCESS_LOCAL_NETWORK
                )
            }
        }
        setContent {
            Domotique1Theme {
                var showSettings by rememberSaveable { mutableStateOf(false) }

                // "Retour" depuis les reglages = retour a l'ecran des volets
                val backCallback = onBackPressedDispatcher.addCallback(enabled = showSettings) {
                    showSettings = false
                }
                backCallback.isEnabled = showSettings



                if (showSettings) {
                    SettingsScreen(
                        repository = repository,
                        onBack = { showSettings = false },
                    )
                } else {
                    VoletsScreen(
                        repository = repository,
                        onSettings = { showSettings = true },
                    )
                }
            }
        }
    }
}
