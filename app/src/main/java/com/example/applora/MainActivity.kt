package com.example.applora

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.example.applora.bluetooth.BluetoothConnectionService
import com.example.applora.bluetooth.ConnectionController
import com.example.applora.bluetooth.ConnectionState
import com.example.applora.bluetooth.ConnectionType
import com.example.applora.bluetooth.OnConnectionListener
import com.example.applora.bluetooth.OnMessageListener
import com.example.applora.data.AppDatabase
import com.example.applora.data.toChatMessage
import com.example.applora.data.toEntity
import com.example.applora.ui.theme.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private var btService: BluetoothConnectionService? = null
    private val controller: ConnectionController? get() = btService?.controller
    private var bound = false

    private val db by lazy { AppDatabase.getInstance(this) }
    private val messageDao by lazy { db.chatMessageDao() }

    private val messages = mutableStateListOf<ChatMessage>()
    private val connectionState = mutableStateOf(ConnectionState.NOT_CONNECTED)
    private val connectedDeviceName = mutableStateOf<String?>(null)
    private val connectedDeviceAddress = mutableStateOf<String?>(null)
    private val lastDeviceName = mutableStateOf<String?>(null)
    private val showConnectionNotification = mutableStateOf(false)
    private val incomingConnectionName = mutableStateOf<String?>(null)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            btService = (binder as BluetoothConnectionService.ConnectionBinder).getService()
            bound = true
            setupListeners()
            syncState()
            loadMessagesForCurrentDevice()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            btService = null
            bound = false
        }
    }

    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val isConnected = data?.getBooleanExtra("connected", false) ?: false
            if (isConnected) {
                connectedDeviceName.value = data?.getStringExtra("device_name")
                connectedDeviceAddress.value = data?.getStringExtra("device_address")
            } else {
                connectedDeviceName.value = null
                connectedDeviceAddress.value = null
            }
            loadMessagesForCurrentDevice()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.all { it.value }) {
            startAndBindService()
        } else {
            Toast.makeText(this, "Permissions Bluetooth requises", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        checkAndRequestPermissions()

        setContent {
            AppLoRaTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    BluetoothChatScreen(
                        messages = messages,
                        connectionState = connectionState.value,
                        connectedDeviceName = connectedDeviceName.value,
                        lastDeviceName = lastDeviceName.value,
                        onSendMessage = { sendMessage(it) },
                        onOpenSettings = { openSettings() },
                        onStartListening = { startListening() },
                        onReconnect = { reconnect() },
                        showConnectionNotification = showConnectionNotification.value,
                        incomingConnectionName = incomingConnectionName.value,
                        onDismissNotification = { showConnectionNotification.value = false }
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
            loadMessagesForCurrentDevice()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (bound) {
            unbindService(serviceConnection)
            bound = false
        }
    }

    private fun checkAndRequestPermissions() {
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
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isEmpty()) {
            startAndBindService()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startAndBindService() {
        if (!BluetoothConnectionService.isRunning) {
            BluetoothConnectionService.start(this)
        }
        BluetoothConnectionService.bind(this, serviceConnection)
    }

    private fun setupListeners() {
        val ctrl = controller ?: return

        ctrl.connectionListener = object : OnConnectionListener {
            override fun onConnecting() {}

            override fun onConnected(device: BluetoothDevice, type: ConnectionType) {
                connectedDeviceName.value = ctrl.connectedDeviceName
                connectedDeviceAddress.value = ctrl.connectedDeviceAddress
                val sysMsg = ChatMessage(
                    content = "Connecté à ${ctrl.connectedDeviceName ?: ctrl.connectedDeviceAddress}",
                    isSent = false,
                    isSystemMessage = true
                )
                messages.add(sysMsg)
                val addr = ctrl.connectedDeviceAddress ?: ""
                lifecycleScope.launch(Dispatchers.IO) {
                    messageDao.insert(sysMsg.toEntity(addr))
                }
                NotificationHelper.showConnectionNotification(this@MainActivity, ctrl.connectedDeviceName)

                if (type == ConnectionType.INCOMING) {
                    incomingConnectionName.value = ctrl.connectedDeviceName ?: ctrl.connectedDeviceAddress
                    showConnectionNotification.value = true
                }
            }

            override fun onConnectionFailed() {
                Toast.makeText(this@MainActivity, "Impossible de se connecter", Toast.LENGTH_SHORT).show()
            }

            override fun onConnectionLost() {
                connectedDeviceName.value = null
                connectedDeviceAddress.value = null
                lastDeviceName.value = ctrl.lastName
                NotificationHelper.cancelConnectionNotification(this@MainActivity)
                Toast.makeText(this@MainActivity, "Connexion perdue", Toast.LENGTH_SHORT).show()
            }

            override fun onDisconnected() {
                connectedDeviceName.value = null
                connectedDeviceAddress.value = null
                lastDeviceName.value = ctrl.lastName
                NotificationHelper.cancelConnectionNotification(this@MainActivity)
            }

            override fun onListening() {}
        }

        ctrl.messageListener = object : OnMessageListener {
            override fun onMessageReceived(message: String, deviceName: String?, deviceAddress: String?) {
                val addr = deviceAddress ?: ctrl.connectedDeviceAddress ?: ctrl.lastAddress ?: ""
                val msg = ChatMessage(
                    content = message,
                    isSent = false,
                    senderName = deviceName
                )
                messages.add(msg)
                lifecycleScope.launch(Dispatchers.IO) {
                    messageDao.insert(msg.toEntity(addr))
                }
                if (!App.isInForeground) {
                    NotificationHelper.showMessageNotification(this@MainActivity, message, deviceName)
                }
            }

            override fun onMessageSent(message: String) {}
            override fun onMessageSendingFailed() {
                Toast.makeText(this@MainActivity, "Erreur lors de l'envoi", Toast.LENGTH_SHORT).show()
            }
        }

        ctrl.onStateChanged = { state ->
            connectionState.value = state
            if (state == ConnectionState.NOT_CONNECTED) {
                NotificationHelper.cancelConnectionNotification(this)
            }
            lastDeviceName.value = ctrl.lastName
        }
    }

    private fun syncState() {
        val ctrl = controller ?: return
        connectionState.value = ctrl.connectionState
        connectedDeviceName.value = ctrl.connectedDeviceName
        connectedDeviceAddress.value = ctrl.connectedDeviceAddress
        lastDeviceName.value = ctrl.lastName
    }

    private fun loadMessagesForCurrentDevice() {
        val addr = connectedDeviceAddress.value
            ?: controller?.lastAddress ?: ""
        if (addr.isEmpty()) {
            messages.clear()
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val list = messageDao.getMessagesForDevice(addr).map { it.toChatMessage() }
            withContext(Dispatchers.Main) {
                messages.clear()
                messages.addAll(list)
            }
        }
    }

    private fun reconnect() {
        if (controller?.reconnect() == true) {
            Toast.makeText(this, "Reconnexion en cours…", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Impossible de reconnecter. Allez dans Paramètres.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendMessage(message: String) {
        val ctrl = controller
        if (ctrl == null || ctrl.connectionState != ConnectionState.CONNECTED) {
            Toast.makeText(this, "Non connecté", Toast.LENGTH_SHORT).show()
            return
        }

        val addr = connectedDeviceAddress.value ?: ""
        val msg = ChatMessage(content = message, isSent = true)
        ctrl.sendMessage(message)
        messages.add(msg)
        if (addr.isNotEmpty()) {
            lifecycleScope.launch(Dispatchers.IO) {
                messageDao.insert(msg.toEntity(addr))
            }
        }
    }

    private fun startListening() {
        controller?.prepareForAccept()
    }

    private fun openSettings() {
        settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
    }
}

// ---- Modèle de données ----

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val content: String,
    val isSent: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val senderName: String? = null,
    val isSystemMessage: Boolean = false
)

// ---- Composables ----

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BluetoothChatScreen(
    messages: List<ChatMessage>,
    connectionState: ConnectionState,
    connectedDeviceName: String?,
    lastDeviceName: String? = null,
    onSendMessage: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onStartListening: () -> Unit,
    onReconnect: () -> Unit = {},
    showConnectionNotification: Boolean = false,
    incomingConnectionName: String? = null,
    onDismissNotification: () -> Unit = {}
) {
    var messageText by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    val isConnected = connectionState == ConnectionState.CONNECTED
    val isListening = connectionState == ConnectionState.LISTENING

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            BluetoothChatTopBar(
                isConnected = isConnected,
                isListening = isListening,
                connectedDeviceName = connectedDeviceName,
                onSettingsClick = onOpenSettings
            )
        },
        bottomBar = {
            MessageInputBar(
                messageText = messageText,
                onMessageChange = { messageText = it },
                onSendClick = {
                    if (messageText.isNotBlank()) {
                        onSendMessage(messageText.trim())
                        messageText = ""
                    }
                },
                isEnabled = isConnected
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (messages.isEmpty()) {
                EmptyConversationPlaceholder(
                    isConnected = isConnected,
                    isListening = isListening,
                    lastDeviceName = lastDeviceName,
                    onStartListening = onStartListening,
                    onOpenSettings = onOpenSettings,
                    onReconnect = onReconnect
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 16.dp)
                ) {
                    items(messages, key = { it.id }) { message ->
                        if (message.isSystemMessage) {
                            SystemMessageBubble(message = message)
                        } else {
                            MessageBubble(message = message)
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = showConnectionNotification,
                enter = slideInVertically() + fadeIn(),
                exit = slideOutVertically() + fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                ConnectionNotificationBanner(
                    deviceName = incomingConnectionName,
                    onDismiss = onDismissNotification
                )
            }
        }
    }
}

@Composable
fun ConnectionNotificationBanner(
    deviceName: String?,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.BluetoothConnected,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Nouvelle connexion",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${deviceName ?: "Un appareil"} s'est connecté",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Fermer")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BluetoothChatTopBar(
    isConnected: Boolean,
    isListening: Boolean,
    connectedDeviceName: String?,
    onSettingsClick: () -> Unit
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    text = "Bluetooth Messenger",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = when {
                        isConnected -> "Connecté à ${connectedDeviceName ?: "un appareil"}"
                        isListening -> "En attente de connexion..."
                        else -> "Non connecté"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = when {
                        isConnected -> MaterialTheme.colorScheme.primary
                        isListening -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        },
        actions = {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            isConnected -> Color(0xFF4CAF50)
                            isListening -> Color(0xFFFF9800)
                            else -> Color(0xFF9E9E9E)
                        }
                    )
            )
            Spacer(modifier = Modifier.width(12.dp))
            IconButton(onClick = onSettingsClick) {
                Icon(Icons.Filled.Settings, contentDescription = "Paramètres Bluetooth")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    )
}

@Composable
fun MessageInputBar(
    messageText: String,
    onMessageChange: (String) -> Unit,
    onSendClick: () -> Unit,
    isEnabled: Boolean = true
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp
    ) {
        Column {
            if (!isEnabled) {
                Text(
                    text = "Connectez-vous à un appareil pour envoyer des messages",
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f))
                        .padding(8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .navigationBarsPadding(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = messageText,
                    onValueChange = onMessageChange,
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(if (isEnabled) "Écrire un message..." else "Non connecté")
                    },
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                        disabledBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                    ),
                    maxLines = 4,
                    enabled = isEnabled
                )

                FilledIconButton(
                    onClick = onSendClick,
                    enabled = messageText.isNotBlank() && isEnabled,
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                    )
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Envoyer",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun MessageBubble(message: ChatMessage) {
    val bubbleColor = if (message.isSent) MessageSent else MessageReceived
    val alignment = if (message.isSent) Alignment.CenterEnd else Alignment.CenterStart
    val bubbleShape = if (message.isSent) {
        RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
    } else {
        RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
    }

    val timeFormatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = alignment
    ) {
        Column(
            horizontalAlignment = if (message.isSent) Alignment.End else Alignment.Start
        ) {
            if (!message.isSent && message.senderName != null) {
                Text(
                    text = message.senderName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                )
            }

            Surface(
                shape = bubbleShape,
                color = bubbleColor,
                modifier = Modifier.widthIn(max = 280.dp)
            ) {
                Text(
                    text = message.content,
                    modifier = Modifier.padding(12.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Text(
                text = timeFormatter.format(Date(message.timestamp)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
fun SystemMessageBubble(message: ChatMessage) {
    val timeFormatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = timeFormatter.format(Date(message.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }
        }
    }
}

@Composable
fun EmptyConversationPlaceholder(
    isConnected: Boolean = false,
    isListening: Boolean = false,
    lastDeviceName: String? = null,
    onStartListening: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onReconnect: () -> Unit = {}
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = when {
                        isConnected -> Icons.Default.BluetoothConnected
                        isListening -> Icons.Default.BluetoothSearching
                        else -> Icons.Default.Bluetooth
                    },
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            Text(
                text = when {
                    isConnected -> "Prêt à discuter !"
                    isListening -> "En attente..."
                    else -> "Non connecté"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )

            Text(
                text = when {
                    isConnected -> "Envoyez votre premier message"
                    isListening -> "En attente qu'un autre appareil se connecte"
                    else -> "Connectez-vous à un autre smartphone pour commencer"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (!isConnected && !isListening) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (lastDeviceName != null) {
                        Button(onClick = onReconnect) {
                            Icon(
                                Icons.Default.BluetoothConnected,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Reconnecter à $lastDeviceName")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = onStartListening) {
                            Icon(
                                Icons.Default.Visibility,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Être visible")
                        }
                        Button(onClick = onOpenSettings) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Se connecter")
                        }
                    }
                }
            }
        }
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun BluetoothChatScreenPreview() {
    AppLoRaTheme {
        BluetoothChatScreen(
            messages = listOf(
                ChatMessage(content = "Salut !", isSent = true),
                ChatMessage(content = "Hello !", isSent = false, senderName = "Samsung A52")
            ),
            connectionState = ConnectionState.CONNECTED,
            connectedDeviceName = "Samsung A52",
            lastDeviceName = null,
            onSendMessage = {},
            onOpenSettings = {},
            onStartListening = {},
            onReconnect = {}
        )
    }
}
