package com.example.applora

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothSearching
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.applora.ui.theme.AppLoRaTheme

class SettingsActivity : ComponentActivity() {

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        manager.adapter
    }

    private val discoveredDevices = mutableStateListOf<BluetoothDevice>()
    private val connectedDevice = mutableStateOf<BluetoothDevice?>(null)
    private val isDiscovering = mutableStateOf(false)

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {

                BluetoothDevice.ACTION_FOUND -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(
                            BluetoothDevice.EXTRA_DEVICE,
                            BluetoothDevice::class.java
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }

                    device?.let {
                        if (!discoveredDevices.any { d -> d.address == it.address }) {
                            discoveredDevices.add(it)
                        }
                    }
                }

                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    isDiscovering.value = true
                }

                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    isDiscovering.value = false
                }
            }
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.all { it.value }) {
                startBluetoothDiscovery()
            } else {
                Toast.makeText(this, "Permissions Bluetooth requises", Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        registerReceiver(bluetoothReceiver, filter)

        setContent {
            AppLoRaTheme {
                SettingsScreen(
                    pairedDevices = getPairedDevices(),
                    discoveredDevices = discoveredDevices,
                    connectedDevice = connectedDevice.value,
                    isDiscovering = isDiscovering.value,
                    onStartDiscovery = { checkPermissionsAndStartDiscovery() },
                    onStopDiscovery = { stopBluetoothDiscovery() },
                    onDeviceSelected = { connectToDevice(it) },
                    onDisconnect = { disconnectDevice() }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopBluetoothDiscovery()
        unregisterReceiver(bluetoothReceiver)
    }

    private fun getPairedDevices(): List<BluetoothDevice> {
        return if (hasConnectPermission()) {
            bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
        } else emptyList()
    }

    private fun connectToDevice(device: BluetoothDevice) {
        if (!hasConnectPermission()) return

        bluetoothAdapter?.cancelDiscovery()
        connectedDevice.value = device

        Toast.makeText(
            this,
            "Connecté à ${device.name ?: device.address}",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun disconnectDevice() {
        connectedDevice.value = null
        Toast.makeText(this, "Déconnecté", Toast.LENGTH_SHORT).show()
    }

    private fun checkPermissionsAndStartDiscovery() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            startBluetoothDiscovery()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun hasConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun startBluetoothDiscovery() {
        discoveredDevices.clear()
        bluetoothAdapter?.cancelDiscovery()
        bluetoothAdapter?.startDiscovery()
    }

    private fun stopBluetoothDiscovery() {
        bluetoothAdapter?.cancelDiscovery()
        isDiscovering.value = false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    pairedDevices: List<BluetoothDevice>,
    discoveredDevices: List<BluetoothDevice>,
    connectedDevice: BluetoothDevice?,
    isDiscovering: Boolean,
    onStartDiscovery: () -> Unit,
    onStopDiscovery: () -> Unit,
    onDeviceSelected: (BluetoothDevice) -> Unit,
    onDisconnect: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Paramètres Bluetooth", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(
                        onClick = { if (isDiscovering) onStopDiscovery() else onStartDiscovery() }
                    ) {
                        Icon(
                            imageVector = if (isDiscovering)
                                Icons.Default.BluetoothSearching
                            else
                                Icons.Default.Refresh,
                            contentDescription = null
                        )
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {

            if (isDiscovering) {
                item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            }

            item { SectionTitle("Appareil connecté") }

            if (connectedDevice == null) {
                item { EmptyCard("Aucun appareil Bluetooth connecté") }
            } else {
                item {
                    DeviceCard(connectedDevice) {}
                }
                item {
                    TextButton(
                        onClick = onDisconnect,
                        modifier = Modifier.padding(start = 16.dp)
                    ) {
                        Text("Se déconnecter")
                    }
                }
            }

            item { SectionTitle("Appareils appairés") }

            if (pairedDevices.isEmpty()) {
                item { EmptyCard("Aucun appareil appairé") }
            } else {
                items(pairedDevices) {
                    DeviceCard(it) { onDeviceSelected(it) }
                }
            }

            item { SectionTitle("Appareils détectés") }

            if (discoveredDevices.isEmpty() && !isDiscovering) {
                item { EmptyCard("Aucun appareil détecté") }
            } else {
                items(discoveredDevices) {
                    DeviceCard(it) { onDeviceSelected(it) }
                }
            }
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(16.dp),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
fun EmptyCard(text: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun DeviceCard(device: BluetoothDevice, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable { onClick() },
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {

            Icon(
                imageVector = Icons.Default.Bluetooth,
                contentDescription = null,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(8.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column {
                Text(
                    text = device.name ?: "Appareil inconnu",
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = device.address,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray
                )
            }
        }
    }
}
