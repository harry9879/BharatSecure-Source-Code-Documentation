package com.bharatsecure.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bharatsecure.app.scam.ScamLevel
import com.bharatsecure.app.scam.ScamVerdict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    // Bumped on every resume so permission rows re-check after visiting Settings.
    private var resumeTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            BharatSecureTheme {
                AppShell(resumeTick)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
    }
}

private enum class Screen(val title: String, val icon: ImageVector) {
    HOME("Call Shield", Icons.Filled.Home),
    RECORDINGS("Recordings", Icons.AutoMirrored.Filled.List),
    RAG("Laptop AI (RAG)", Icons.Filled.Share),
    SPEECH("Speech engine", Icons.Filled.Settings),
    SETUP("Setup & permissions", Icons.Filled.CheckCircle),
}

/** Side menu + top bar around the screens. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppShell(resumeTick: Int) {
    val context = LocalContext.current
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    val protection by ProtectionState.state.collectAsStateWithLifecycle()
    var recordings by remember { mutableStateOf(Recordings.list(context)) }
    // Reload the list whenever a recording finishes or the app comes back.
    LaunchedEffect(protection.running, resumeTick) { recordings = Recordings.list(context) }

    fun go(to: Screen) {
        screen = to
        scope.launch { drawer.close() }
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                Column(
                    Modifier.fillMaxWidth().background(Brand.Navy).padding(horizontal = 24.dp, vertical = 28.dp),
                ) {
                    Text("🛡", style = MaterialTheme.typography.displaySmall)
                    Text("BharatSecure", color = Color.White, style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold)
                    Text("Scam protection for calls & SMS", color = Color(0xFFB9C8E8),
                        style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
                Screen.entries.forEach { s ->
                    NavigationDrawerItem(
                        label = {
                            Text(if (s == Screen.RECORDINGS && recordings.isNotEmpty()) "${s.title} (${recordings.size})"
                            else s.title)
                        },
                        icon = { Icon(s.icon, contentDescription = null) },
                        selected = s == screen,
                        onClick = { go(s) },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
                NavigationDrawerItem(
                    label = { Text("SMS Shield (ScamShield app)") },
                    icon = { Icon(Icons.Filled.Email, contentDescription = null) },
                    selected = false,
                    onClick = {
                        scope.launch { drawer.close() }
                        SmsShield.open(context)
                    },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        },
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = { Text(screen.title, fontWeight = FontWeight.SemiBold) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawer.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "Open menu")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Brand.Navy,
                        titleContentColor = Color.White,
                        navigationIconContentColor = Color.White,
                    ),
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Spacer(Modifier.height(4.dp)) }
                when (screen) {
                    Screen.HOME -> {
                        item { ProtectionCard(protection) }
                        item { StatusTiles(resumeTick, onOpen = ::go) }
                        item { SmsShieldCard(resumeTick) }
                        item { HowItWorksCard() }
                    }
                    Screen.RECORDINGS -> {
                        if (recordings.isEmpty()) {
                            item { EmptyState("No recordings yet", "Calls you protect are saved here with their transcript and scam verdict.") }
                        }
                        items(recordings, key = { it.name }) { rec ->
                            RecordingRow(rec, onDeleted = { recordings = Recordings.list(context) })
                        }
                    }
                    Screen.RAG -> item { RagServerCard() }
                    Screen.SPEECH -> {
                        item { SpeechEngineCard() }
                        item { SpeechModelCard() }
                    }
                    Screen.SETUP -> item { PermissionsCard(resumeTick) }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

/** Three tappable tiles: setup progress, speech engine, laptop AI. */
@Composable
private fun StatusTiles(resumeTick: Int, onOpen: (Screen) -> Unit) {
    val context = LocalContext.current
    key(resumeTick) {}
    val (done, total) = setupProgress(context)
    val engine = SpeechModels.engine(context)
    val online = engine == SpeechEngine.ONLINE && SpeechModels.apiKey(context).isNotBlank()
    val ragUrl = RagSettings.url(context)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusTile("Setup", "$done/$total", if (done == total) Brand.Safe else Brand.Warning,
            Modifier.weight(1f)) { onOpen(Screen.SETUP) }
        StatusTile("Speech", if (online) "Online" else "Offline", Brand.Blue, Modifier.weight(1f)) { onOpen(Screen.SPEECH) }
        StatusTile("Laptop AI", if (ragUrl.isBlank()) "Off" else "On", if (ragUrl.isBlank()) Color.Gray else Brand.Safe,
            Modifier.weight(1f)) { onOpen(Screen.RAG) }
    }
}

@Composable
private fun StatusTile(label: String, value: String, colour: Color, modifier: Modifier, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier, colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = colour)
        }
    }
}

/** Entry to the team's separate SMS app. */
@Composable
private fun SmsShieldCard(resumeTick: Int) {
    val context = LocalContext.current
    key(resumeTick) {}
    val installed = SmsShield.isInstalled(context)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("✉️", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("SMS Shield", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(
                    if (installed) "Checks every SMS for scams, opens links safely, reports to cyber-crime."
                    else "Not installed yet. Install the ScamShield app to protect SMS too.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            if (installed) {
                Button(onClick = { SmsShield.open(context) },
                    colors = ButtonDefaults.buttonColors(containerColor = Brand.Saffron)) { Text("Open") }
            }
        }
    }
}

@Composable
private fun HowItWorksCard() {
    Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("How it works", fontWeight = FontWeight.Bold)
            listOf(
                "1. A call comes in: tap \"Enable Protection Mode\" on the notification.",
                "2. Answer and put the call on speaker.",
                "3. BharatSecure listens, transcribes and checks for scams every few seconds.",
                "4. If it's a scam you get a loud alert, the reason, and a button to call 1930.",
            ).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun EmptyState(title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("🗂", style = MaterialTheme.typography.displaySmall)
        Text(title, fontWeight = FontWeight.Bold)
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ProtectionCard(state: ProtectionUiState) {
    val context = LocalContext.current
    val running = state.running
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (running) Color(0xFFFFEBEE) else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (running) "Protection Mode ON" else "Protection Mode off",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (running) Color(0xFFC62828) else Color.Unspecified,
            )
            if (running) {
                Text("${state.source?.label ?: ""} · ${state.caller ?: "unknown caller"}")
                VerdictBanner(state.verdict)
                RagPanel(state)
                Text(state.status, style = MaterialTheme.typography.bodySmall)
                Text("Mic level", style = MaterialTheme.typography.labelSmall)
                LinearProgressIndicator(progress = { state.level }, modifier = Modifier.fillMaxWidth())
                Text("Scam score ${(state.scamScore * 100).toInt()}%", style = MaterialTheme.typography.labelSmall)
                LinearProgressIndicator(
                    progress = { state.scamScore.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                    color = when {
                        state.scamScore >= 0.9 -> Color(0xFFC62828)
                        state.scamScore >= 0.5 -> Color(0xFFEF6C00)
                        else -> Color(0xFF2E7D32)
                    },
                )

                Box(
                    Modifier.fillMaxWidth()
                        .background(Color.White, RoundedCornerShape(8.dp))
                        .padding(12.dp)
                ) {
                    Column {
                        Text("Live transcript", style = MaterialTheme.typography.labelMedium)
                        state.transcript.takeLast(8).forEach { Text(it) }
                        if (state.partial.isNotBlank()) Text(state.partial, color = Color.Gray)
                        if (state.transcript.isEmpty() && state.partial.isBlank()) {
                            Text("Listening…", color = Color.Gray)
                        }
                    }
                }
                Button(
                    onClick = { ProtectionService.stop() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828)),
                ) { Text("Stop and save") }
            } else {
                if (state.status.isNotBlank()) Text("Last: ${state.status}", style = MaterialTheme.typography.bodySmall)
                if (state.verdict.level != ScamLevel.SAFE) VerdictBanner(state.verdict)
                RagPanel(state)
                Button(onClick = {
                    if (!ProtectionService.start(context, CallSource.MANUAL, null)) {
                        android.widget.Toast.makeText(context, "Allow microphone first", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Start Protection Mode now") }
            }
        }
    }
}

@Composable
private fun VerdictBanner(verdict: ScamVerdict) {
    val context = LocalContext.current
    val (background, title) = when (verdict.level) {
        ScamLevel.SAFE -> Color(0xFFE8F5E9) to "✅ No scam signs so far"
        ScamLevel.WARNING -> Color(0xFFFFF3E0) to "⚠️ Possible scam: ${verdict.type?.label}"
        ScamLevel.SURE -> Color(0xFFC62828) to "🛑 SCAM CALL: ${verdict.type?.label}"
    }
    val textColor = if (verdict.level == ScamLevel.SURE) Color.White else Color.Unspecified
    Column(
        Modifier.fillMaxWidth().background(background, RoundedCornerShape(8.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(title, fontWeight = FontWeight.Bold, color = textColor)
        verdict.type?.let { Text(it.advice, color = textColor, style = MaterialTheme.typography.bodyMedium) }
        if (verdict.level != ScamLevel.SAFE) {
            Button(
                onClick = { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:1930"))) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0)),
            ) { Text("Call 1930 (Cyber helpline)") }
        }
    }
}

/** (granted, total) for the setup checklist, same checks as PermissionsCard. */
private fun setupProgress(context: Context): Pair<Int, Int> {
    val checks = listOfNotNull(
        granted(context, Manifest.permission.RECORD_AUDIO),
        granted(context, Manifest.permission.READ_PHONE_STATE) && granted(context, Manifest.permission.READ_CALL_LOG),
        if (Build.VERSION.SDK_INT >= 33) granted(context, Manifest.permission.POST_NOTIFICATIONS) else null,
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName),
        CallAudioAccessibilityService.isEnabled(context),
    )
    return checks.count { it } to checks.size
}

@Composable
private fun PermissionsCard(resumeTick: Int) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        tick++
    }
    // Read both ticks so the rows re-check after a grant or a return from Settings.
    key(resumeTick, tick) {}

    val mic = granted(context, Manifest.permission.RECORD_AUDIO)
    val phone = granted(context, Manifest.permission.READ_PHONE_STATE) &&
        granted(context, Manifest.permission.READ_CALL_LOG)
    val notifications = Build.VERSION.SDK_INT < 33 || granted(context, Manifest.permission.POST_NOTIFICATIONS)
    val listener = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    val accessibility = CallAudioAccessibilityService.isEnabled(context)

    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Setup", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            PermissionRow("Microphone (record calls on speaker)", mic) {
                launcher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            }
            PermissionRow("Phone calls (detect incoming calls)", phone) {
                launcher.launch(arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG))
            }
            if (Build.VERSION.SDK_INT >= 33) {
                PermissionRow("Notifications (call alerts)", notifications) {
                    launcher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                }
            }
            PermissionRow("Notification access (detect WhatsApp calls)", listener) {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            PermissionRow("Accessibility (hear audio during calls)", accessibility) {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            if (!accessibility) {
                Text(
                    "Without this, Android mutes the microphone during calls and recordings are silent. " +
                        "Open Installed apps → BharatSecure call protection → On.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun PermissionRow(label: String, ok: Boolean, onGrant: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(if (ok) "✅" else "⚠️")
        Spacer(Modifier.width(8.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        if (!ok) TextButton(onClick = onGrant) { Text("Allow") }
    }
}

/** Live verdict from the laptop RAG server during (and after) a call. */
@Composable
private fun RagPanel(state: ProtectionUiState) {
    if (state.ragStatus.isEmpty()) return
    val rag = state.rag
    val colour = when (rag?.level) {
        "SCAM" -> Color(0xFFC62828)
        "WARNING" -> Color(0xFFEF6C00)
        else -> Color(0xFF2E7D32)
    }
    Column(
        Modifier.fillMaxWidth().background(Color(0xFFF3F6FC), RoundedCornerShape(8.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("💻 Laptop AI (RAG)", fontWeight = FontWeight.Bold)
        Text(state.ragStatus, style = MaterialTheme.typography.bodySmall,
            color = if (state.ragStatus.startsWith("Laptop offline")) Color(0xFFC62828) else Color.Gray)
        if (rag != null) {
            Text(
                "${rag.level}${rag.typeName?.let { " · $it" }.orEmpty()}  ${(rag.score * 100).toInt()}%",
                fontWeight = FontWeight.Bold, color = colour,
            )
            LinearProgressIndicator(progress = { rag.score.toFloat() }, modifier = Modifier.fillMaxWidth(), color = colour)
            rag.reason?.let { Text("Why: $it", style = MaterialTheme.typography.bodyMedium) }
            rag.advice?.let { Text("What to do: $it", style = MaterialTheme.typography.bodyMedium) }
        } else {
            Text("Waiting for the first sentence…", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Address of the laptop running rag/server.py, with a connection test. */
@Composable
private fun RagServerCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(RagSettings.url(context)) }
    var result by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Laptop AI (RAG server)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(
                "During Protection Mode each transcript sentence is sent to the laptop, which replies with a " +
                    "scam score and the reason. Phone and laptop must be on the same hotspot. Leave empty to turn off.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it; result = null },
                label = { Text("Laptop address, e.g. 10.30.137.250:8000") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    RagSettings.saveUrl(context, url)
                    url = RagSettings.url(context)
                    result = if (url.isEmpty()) "Laptop AI turned off" else "Saved"
                }) { Text("Save") }
                OutlinedButton(enabled = !testing && url.isNotBlank(), onClick = {
                    testing = true
                    result = "Testing…"
                    val base = RagSettings.normalize(url)
                    scope.launch {
                        result = withContext(Dispatchers.IO) {
                            try {
                                val h = RagClient.health(base)
                                val keys = h.optBoolean("groq_key") && h.optBoolean("deepseek_key")
                                "✅ Connected · ${h.optInt("passages")} examples · " +
                                    if (keys) "DeepSeek on" else "⚠️ laptop has no DeepSeek key"
                            } catch (e: Exception) {
                                "❌ Can't reach $base (${e.message ?: e.javaClass.simpleName}). Same hotspot? Server running?"
                            }
                        }
                        testing = false
                    }
                }) { Text("Test connection") }
            }
            result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun SpeechEngineCard() {
    val context = LocalContext.current
    var engine by remember { mutableStateOf(SpeechModels.engine(context)) }
    var key by remember { mutableStateOf(SpeechModels.apiKey(context)) }
    var saved by remember { mutableStateOf(key.isNotBlank()) }

    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Speech engine", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SpeechEngine.entries.forEach { e ->
                    FilterChip(
                        selected = e == engine,
                        onClick = {
                            engine = e
                            SpeechModels.selectEngine(context, e)
                        },
                        label = { Text(e.label) },
                    )
                }
            }
            if (engine == SpeechEngine.ONLINE) {
                Text(
                    "Whisper large-v3 (via Groq) understands Hindi, English and Hinglish and returns English text. " +
                        "Call audio is sent to Groq in 6-second pieces while Protection Mode is on. " +
                        "Without a key or internet, the app uses the offline models.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it; saved = false },
                    label = { Text("Groq API key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = {
                        SpeechModels.saveApiKey(context, key)
                        saved = key.isNotBlank()
                    }) { Text("Save key") }
                    Spacer(Modifier.width(12.dp))
                    Text(if (saved) "✅ Key saved" else "⚠️ No key saved", style = MaterialTheme.typography.bodySmall)
                }
                Text("Get a free key at console.groq.com → API Keys.", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("Vosk models run on the phone; audio never leaves it.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SpeechModelCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var language by remember { mutableStateOf(SpeechModels.selectedLanguage(context)) }
    // Bumped after a download so the installed checks re-run.
    var installTick by remember { mutableIntStateOf(0) }
    var downloading by remember { mutableStateOf<SpeechModel?>(null) }
    var progress by remember { mutableStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }

    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Speech-to-text (offline)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Language spoken on the call:", style = MaterialTheme.typography.bodySmall)
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SpeechLanguage.entries.forEach { lang ->
                    FilterChip(
                        selected = lang == language,
                        onClick = {
                            language = lang
                            SpeechModels.selectLanguage(context, lang)
                        },
                        label = { Text(lang.label) },
                    )
                }
            }
            if (language == SpeechLanguage.HINGLISH) {
                Text(
                    "Runs the Hindi and English models together and keeps whichever is surer of each sentence.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            key(installTick) {
                language.models.forEach { model ->
                    when {
                        SpeechModels.isInstalled(context, model) -> Text("✅ ${model.label} model installed")
                        downloading == model -> {
                            Text("Downloading ${model.label} model…")
                            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        }
                        else -> OutlinedButton(
                            enabled = downloading == null,
                            onClick = {
                                error = null
                                progress = 0f
                                downloading = model
                                scope.launch {
                                    try {
                                        SpeechModels.download(context, model) { p -> progress = p }
                                    } catch (e: Exception) {
                                        error = e.message ?: "Download failed"
                                    } finally {
                                        downloading = null
                                        installTick++
                                    }
                                }
                            },
                        ) { Text("Download ${model.label} model (~${model.sizeMb} MB)") }
                    }
                }
                val missing = language.models.filterNot { SpeechModels.isInstalled(context, it) }
                if (missing.size == language.models.size) {
                    Text(
                        "Until a model is downloaded, calls are recorded without a transcript.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else if (missing.isNotEmpty()) {
                    Text(
                        "Works now with the installed model; download the other for full ${language.label}.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text("Transcription works without internet.", style = MaterialTheme.typography.bodySmall)
                }
            }
            error?.let { Text(it, color = Color(0xFFC62828)) }
        }
    }
}

@Composable
private fun RecordingRow(rec: Recording, onDeleted: () -> Unit) {
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var showText by remember { mutableStateOf(false) }
    DisposableEffect(rec.name) { onDispose { player?.release() } }

    Card {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(rec.name, fontWeight = FontWeight.SemiBold)
            Text("${rec.audio.length() / 1024} KB", style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick = {
                    val p = player
                    if (p != null) {
                        p.release()
                        player = null
                    } else {
                        player = MediaPlayer().apply {
                            setDataSource(rec.audio.absolutePath)
                            setOnCompletionListener { it.release(); player = null }
                            prepare()
                            start()
                        }
                    }
                }) { Text(if (player != null) "Stop" else "Play") }
                TextButton(onClick = { showText = !showText }) {
                    Text(if (showText) "Hide transcript" else "Transcript")
                }
                TextButton(onClick = {
                    player?.release()
                    player = null
                    Recordings.delete(rec)
                    onDeleted()
                }) { Text("Delete") }
            }
            if (showText) {
                Text(
                    if (rec.transcript.exists()) rec.transcript.readText() else "(No transcript saved)",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun granted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
