package com.example.applora

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.applora.bluetooth.BluetoothConnectionService
import com.example.applora.bluetooth.ConnectionController
import com.example.applora.bluetooth.ConnectionState
import com.example.applora.bluetooth.ConnectionType
import com.example.applora.bluetooth.OnConnectionListener
import com.example.applora.ui.theme.AppLoRaTheme

class SettingsActivity : ComponentActivity() {

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    private var btService: BluetoothConnectionService? = null
    private val controller: ConnectionController? get() = btService?.controller
    private var bound = false

    private val discoveredDevices = mutableStateListOf<BluetoothDevice>()
    private val isDiscovering = mutableStateOf(false)
    private val isListening = mutableStateOf(false)
    private val connectionState = mutableStateOf(ConnectionState.NOT_CONNECTED)
    private val connectedDeviceName = mutableStateOf<String?>(null)
    private val connectedDeviceAddress = mutableStateOf<String?>(null)
    private val showConnectionDialog = mutableStateOf(false)
    private val incomingDeviceName = mutableStateOf<String?>(null)
    private val incomingDeviceAddress = mutableStateOf<String?>(null)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            btService = (binder as BluetoothConnectionService.ConnectionBinder).getService()
            bound = true
            setupListeners()
            syncState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            btService = null
            bound = false
        }
    }

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
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> isDiscovering.value = true
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> isDiscovering.value = false
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

    private val discoverableLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode > 0) {
                startListeningForConnections()
            } else {
                Toast.makeText(this, "Mode découvrable requis pour recevoir des connexions", Toast.LENGTH_LONG).show()
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

        if (BluetoothConnectionService.isRunning) {
            BluetoothConnectionService.bind(this, serviceConnection)
        }

        setContent {
            AppLoRaTheme {
                SettingsScreen(
                    pairedDevices = getPairedDevices(),
                    discoveredDevices = discoveredDevices,
                    connectedDeviceName = connectedDeviceName.value,
                    connectedDeviceAddress = connectedDeviceAddress.value,
                    isDiscovering = isDiscovering.value,
                    isListening = isListening.value,
                    connectionState = connectionState.value,
                    onStartDiscovery = { checkPermissionsAndStartDiscovery() },
                    onStopDiscovery = { stopBluetoothDiscovery() },
                    onDeviceSelected = { connectToDevice(it) },
                    onDisconnect = { disconnectDevice() },
                    onStartListening = { makeDiscoverableAndListen() },
                    onStopListening = { stopListening() },
                    onBack = { finish() }
                )

                if (showConnectionDialog.value) {
                    ConnectionRequestDialog(
                        deviceName = incomingDeviceName.value,
                        deviceAddress = incomingDeviceAddress.value,
                        onDismiss = { showConnectionDialog.value = false }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (bound) {
            setupListeners()
            syncState()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopBluetoothDiscovery()
        unregisterReceiver(bluetoothReceiver)
        if (bound) {
            unbindService(serviceConnection)
            bound = false
        }
    }

    private fun setupListeners() {
        val ctrl = controller ?: return

        ctrl.connectionListener = object : OnConnectionListener {
            override fun onConnecting() {}

            override fun onConnected(device: BluetoothDevice, type: ConnectionType) {
                connectedDeviceName.value = ctrl.connectedDeviceName
                connectedDeviceAddress.value = ctrl.connectedDeviceAddress

                if (type == ConnectionType.INCOMING) {
                    incomingDeviceName.value = ctrl.connectedDeviceName
                    incomingDeviceAddress.value = ctrl.connectedDeviceAddress
                    showConnectionDialog.value = true
                    Toast.makeText(
                        this@SettingsActivity,
                        "Connexion entrante de ${ctrl.connectedDeviceName ?: ctrl.connectedDeviceAddress}",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    showConnectionDialog.value = false
                }

                val resultIntent = Intent().apply {
                    putExtra("connected", true)
                    putExtra("device_name", ctrl.connectedDeviceName)
                    putExtra("device_address", ctrl.connectedDeviceAddress)
                }
                setResult(Activity.RESULT_OK, resultIntent)

                Toast.makeText(
                    this@SettingsActivity,
                    "Connecté à ${ctrl.connectedDeviceName}",
                    Toast.LENGTH_SHORT
                ).show()
            }

            override fun onConnectionFailed() {
                Toast.makeText(this@SettingsActivity, "Impossible de se connecter", Toast.LENGTH_SHORT).show()
            }

            override fun onConnectionLost() {
                connectedDeviceName.value = null
                connectedDeviceAddress.value = null
                Toast.makeText(this@SettingsActivity, "Connexion perdue", Toast.LENGTH_SHORT).show()
            }

            override fun onDisconnected() {
                connectedDeviceName.value = null
                connectedDeviceAddress.value = null
            }

            override fun onListening() {
                isListening.value = true
            }
        }

        ctrl.onStateChanged = { state ->
            connectionState.value = state
            isListening.value = state == ConnectionState.LISTENING
        }
    }

    private fun syncState() {
        val ctrl = controller ?: return
        connectionState.value = ctrl.connectionState
        connectedDeviceName.value = ctrl.connectedDeviceName
        connectedDeviceAddress.value = ctrl.connectedDeviceAddress
        isListening.value = ctrl.connectionState == ConnectionState.LISTENING
    }

    @SuppressLint("MissingPermission")
    private fun getPairedDevices(): List<BluetoothDevice> {
        return if (hasConnectPermission()) {
            try {
                bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
            } catch (e: SecurityException) {
                emptyList()
            }
        } else emptyList()
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        if (!hasConnectPermission()) return
        try {
            bluetoothAdapter?.cancelDiscovery()
        } catch (_: SecurityException) { }
        controller?.connect(device)
    }

    private fun disconnectDevice() {
        controller?.disconnect()
        connectedDeviceName.value = null
        connectedDeviceAddress.value = null

        setResult(Activity.RESULT_OK, Intent().apply { putExtra("connected", false) })
        Toast.makeText(this, "Déconnecté", Toast.LENGTH_SHORT).show()
    }

    private fun makeDiscoverableAndListen() {
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
            putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
        }
        discoverableLauncher.launch(intent)
    }

    private fun startListeningForConnections() {
        controller?.prepareForAccept()
        Toast.makeText(this, "En attente de connexions...", Toast.LENGTH_SHORT).show()
    }

    private fun stopListening() {
        controller?.stop()
        isListening.value = false
    }

    private fun checkPermissionsAndStartDiscovery() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
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
                this, Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    @SuppressLint("MissingPermission")
    private fun startBluetoothDiscovery() {
        if (!hasScanPermission()) return
        discoveredDevices.clear()
        try {
            bluetoothAdapter?.cancelDiscovery()
            bluetoothAdapter?.startDiscovery()
        } catch (e: SecurityException) {
            Toast.makeText(this, "Permission de scan Bluetooth requise", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopBluetoothDiscovery() {
        try {
            bluetoothAdapter?.cancelDiscovery()
        } catch (_: SecurityException) { }
        isDiscovering.value = false
    }

    private fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(
                this, Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        } else true
    }
}

// ---- Composables ----

@Composable
fun ConnectionRequestDialog(
    deviceName: String?,
    deviceAddress: String?,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.BluetoothConnected,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp)
            )
        },
        title = {
            Text(text = "Connexion établie !", textAlign = TextAlign.Center)
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = deviceName ?: "Appareil inconnu",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = deviceAddress ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Vous pouvez maintenant échanger des messages",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("OK") }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    pairedDevices: List<BluetoothDevice>,
    discoveredDevices: List<BluetoothDevice>,
    connectedDeviceName: String?,
    connectedDeviceAddress: String?,
    isDiscovering: Boolean,
    isListening: Boolean,
    connectionState: ConnectionState,
    onStartDiscovery: () -> Unit,
    onStopDiscovery: () -> Unit,
    onDeviceSelected: (BluetoothDevice) -> Unit,
    onDisconnect: () -> Unit,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onBack: () -> Unit
) {
    val isConnected = connectionState == ConnectionState.CONNECTED
    val isConnecting = connectionState == ConnectionState.CONNECTING

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connexion Bluetooth", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { if (isDiscovering) onStopDiscovery() else onStartDiscovery() }
                    ) {
                        Icon(
                            imageVector = if (isDiscovering) Icons.Default.BluetoothSearching
                            else Icons.Default.Refresh,
                            contentDescription = if (isDiscovering) "Arrêter la recherche" else "Rechercher"
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
            if (isDiscovering || isConnecting) {
                item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
            }

            item {
                ConnectionStatusCard(
                    isConnected = isConnected,
                    isConnecting = isConnecting,
                    isListening = isListening,
                    connectedDeviceName = connectedDeviceName,
                    connectedDeviceAddress = connectedDeviceAddress,
                    onDisconnect = onDisconnect,
                    onStartListening = onStartListening,
                    onStopListening = onStopListening
                )
            }

            item { SectionTitle("Appareils appairés") }

            if (pairedDevices.isEmpty()) {
                item { EmptyCard("Aucun appareil appairé") }
            } else {
                items(pairedDevices) { device ->
                    DeviceCard(
                        device = device,
                        isConnected = connectedDeviceAddress == device.address,
                        onClick = {
                            if (connectedDeviceAddress != device.address && !isConnecting) {
                                onDeviceSelected(device)
                            }
                        }
                    )
                }
            }

            item { SectionTitle("Appareils à proximité") }

            if (discoveredDevices.isEmpty() && !isDiscovering) {
                item {
                    EmptyCard("Aucun appareil détecté\nAppuyez sur le bouton rafraîchir pour rechercher")
                }
            } else {
                items(discoveredDevices) { device ->
                    DeviceCard(
                        device = device,
                        isConnected = connectedDeviceAddress == device.address,
                        onClick = {
                            if (connectedDeviceAddress != device.address && !isConnecting) {
                                onDeviceSelected(device)
                            }
                        }
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }
}

@Composable
fun ConnectionStatusCard(
    isConnected: Boolean,
    isConnecting: Boolean,
    isListening: Boolean,
    connectedDeviceName: String?,
    connectedDeviceAddress: String?,
    onDisconnect: () -> Unit,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isConnected -> MaterialTheme.colorScheme.primaryContainer
                isConnecting -> MaterialTheme.colorScheme.secondaryContainer
                isListening -> MaterialTheme.colorScheme.tertiaryContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = when {
                        isConnected -> Icons.Default.BluetoothConnected
                        isConnecting -> Icons.Default.BluetoothSearching
                        isListening -> Icons.Default.BluetoothSearching
                        else -> Icons.Default.BluetoothDisabled
                    },
                    contentDescription = null,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            when {
                                isConnected -> MaterialTheme.colorScheme.primary
                                isConnecting -> MaterialTheme.colorScheme.secondary
                                isListening -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.outline
                            }
                        )
                        .padding(12.dp),
                    tint = Color.White
                )

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = when {
                            isConnected -> "Connecté"
                            isConnecting -> "Connexion en cours..."
                            isListening -> "En attente de connexion"
                            else -> "Non connecté"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    if (isConnected && connectedDeviceName != null) {
                        Text(text = connectedDeviceName, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = connectedDeviceAddress ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (isListening) {
                        Text(
                            text = "L'autre smartphone peut se connecter à vous",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isConnected) {
                    OutlinedButton(onClick = onDisconnect, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.LinkOff, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Déconnecter")
                    }
                } else if (isListening) {
                    OutlinedButton(onClick = onStopListening, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Close, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Arrêter l'écoute")
                    }
                } else if (!isConnecting) {
                    Button(onClick = onStartListening, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Visibility, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Être visible")
                    }
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
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@SuppressLint("MissingPermission")
@Composable
fun DeviceCard(
    device: BluetoothDevice,
    isConnected: Boolean = false,
    onClick: () -> Unit
) {
    val deviceName = remember(device) {
        try {
            device.name ?: "Appareil inconnu"
        } catch (e: SecurityException) {
            "Appareil inconnu"
        }
    }
    val deviceAddress = remember(device) { device.address }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable { onClick() },
        elevation = CardDefaults.cardElevation(2.dp),
        colors = if (isConnected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isConnected) Icons.Default.BluetoothConnected else Icons.Default.Bluetooth,
                contentDescription = null,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (isConnected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.primaryContainer
                    )
                    .padding(8.dp),
                tint = if (isConnected) Color.White else MaterialTheme.colorScheme.onPrimaryContainer
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(text = deviceName, fontWeight = FontWeight.SemiBold)
                Text(
                    text = deviceAddress,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray
                )
            }

            if (isConnected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Connecté",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
