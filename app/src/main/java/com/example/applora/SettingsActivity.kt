package com.example.applora

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import com.example.applora.ui.theme.AppLoRaTheme

import android.content.Intent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.*
import androidx.compose.ui.tooling.preview.Preview

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.widget.Toast


class SettingsActivity : ComponentActivity() {

    // Initialisation dse valeurs bluetooth
    private val bluetoothAdapter : BluetoothAdapter? by lazy {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        checkBluetoothState() // Appel du check bluetooth

        setContent {
            AppLoRaTheme {
                SettingsScreen()
            }
        }
    }
    private fun checkBluetoothState() { // Fonction check statut bluetooth
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth non disponible", Toast.LENGTH_SHORT).show()
        } else {
                Toast.makeText(this, "Bluetooth disponible", Toast.LENGTH_SHORT).show()
            }
        }
}

@OptIn(ExperimentalMaterial3Api::class)
@Preview
@Composable
fun SettingsScreen() {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Paramètres", fontWeight = FontWeight.SemiBold) }
            )
        }
    ) { padding ->
        // TODO: ajouter les éléments de paramètres ici
        Surface(modifier = Modifier.fillMaxSize()) {
            // contenu placeholder
            Text(
                text = "Ici, vos paramètres LoRa",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}
