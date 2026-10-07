package com.jarvis.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import android.net.Uri
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope

private val Cyan = Color(0xFF38E1FF)
private val CyanDim = Color(0xFF0E5A6B)
private val Bg = Color(0xFF05080F)
private val Panel = Color(0xFF0B1220)

class MainActivity : ComponentActivity() {

    companion object {
        const val ACTION_SCREEN_RECORD = "com.jarvis.assistant.SCREEN_RECORD"
    }

    private lateinit var voice: VoiceListener

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {}

    private val screenCapture = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { res ->
        val data = res.data
        if (res.resultCode == RESULT_OK && data != null) {
            val i = Intent(this, ScreenRecordService::class.java)
                .putExtra(ScreenRecordService.EXTRA_CODE, res.resultCode)
                .putExtra(ScreenRecordService.EXTRA_DATA, data)
            ContextCompat.startForegroundService(this, i)
            Jarvis.add("Jarvis: Gravando a tela.")
            moveTaskToBack(true)
        } else {
            Jarvis.add("Jarvis: Gravação de tela cancelada.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        voice = VoiceListener(this)
        Jarvis.speaker(this)
        permissions.launch(requiredPermissions())
        handleIntent(intent)
        setContent {
            JarvisScreen(
                onMic = ::listenOnce,
                onToggleListening = ::toggleService,
                onAccessibility = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                onStopRecording = { Jarvis.add("Jarvis: " + Recorders.stopAll()) },
                onSend = { Jarvis.handle(this, it, lifecycleScope) },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onDestroy() {
        voice.destroy()
        super.onDestroy()
    }

    private fun handleIntent(i: Intent?) {
        if (i != null && i.action == ACTION_SCREEN_RECORD) {
            i.action = null
            val mpm = getSystemService(MediaProjectionManager::class.java)!!
            screenCapture.launch(mpm.createScreenCaptureIntent())
        }
    }

    private fun requiredPermissions(): Array<String> {
        val list = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.CAMERA,
        )
        if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
        return list.toTypedArray()
    }

    private fun hasMic() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

    private fun listenOnce() {
        if (!hasMic()) { permissions.launch(requiredPermissions()); return }
        if (JarvisService.running.value) {
            Jarvis.add("Jarvis: A escuta contínua já está ativa. Basta dizer \"Jarvis\".")
            return
        }
        Jarvis.status.value = "Ouvindo..."
        voice.listen(
            onResult = { Jarvis.handle(this, it, lifecycleScope) },
            onFail = { Jarvis.status.value = "Não ouvi nada, senhor." },
            onLevel = { Jarvis.level.value = it },
        )
    }

    private fun toggleService(on: Boolean) {
        val i = Intent(this, JarvisService::class.java)
        if (on) {
            if (!hasMic()) { permissions.launch(requiredPermissions()); return }
            voice.destroy()
            ContextCompat.startForegroundService(this, i)
        } else {
            stopService(i)
        }
    }
}

@Composable
private fun JarvisScreen(
    onMic: () -> Unit,
    onToggleListening: (Boolean) -> Unit,
    onAccessibility: () -> Unit,
    onStopRecording: () -> Unit,
    onSend: (String) -> Unit,
) {
    val log by Jarvis.log.collectAsState()
    val status by Jarvis.status.collectAsState()
    val level by Jarvis.level.collectAsState()
    val listening by JarvisService.running.collectAsState()
    val accessibility by JarvisAccessibilityService.connected.collectAsState()
    val recording by Recorders.recording.collectAsState()
    val pulse by animateFloatAsState(1f + level * 0.18f, label = "pulse")
    var typed by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(log.size) { if (log.isNotEmpty()) listState.animateScrollToItem(log.size - 1) }

    MaterialTheme(colorScheme = darkColorScheme(primary = Cyan, background = Bg, surface = Panel)) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Bg)
                .systemBarsPadding()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "J.A.R.V.I.S.", color = Cyan, fontSize = 30.sp, fontWeight = FontWeight.Bold,
                letterSpacing = 8.sp, fontFamily = FontFamily.Monospace,
            )
            Text(status, color = Cyan.copy(alpha = 0.7f), fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))

            Spacer(Modifier.height(24.dp))

            // "Reator" central: toque para falar.
            Box(
                Modifier
                    .size(190.dp)
                    .scale(pulse)
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(Cyan.copy(alpha = 0.35f), Bg)))
                    .border(3.dp, Cyan, CircleShape)
                    .clickable(onClick = onMic),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(110.dp).border(2.dp, Cyan.copy(alpha = 0.6f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (listening) "ATIVO" else "FALAR", color = Cyan, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
            }

            Spacer(Modifier.height(20.dp))

            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel).padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Escuta contínua", color = Color.White)
                    Text("Diga \"Jarvis\" a qualquer momento", color = Color.Gray, fontSize = 12.sp)
                }
                Switch(checked = listening, onCheckedChange = onToggleListening)
            }

            Spacer(Modifier.height(8.dp))

            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel)
                    .clickable(onClick = onAccessibility).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Acessibilidade", color = Color.White, modifier = Modifier.weight(1f))
                Text(
                    if (accessibility) "● Ativa" else "○ Toque para ativar",
                    color = if (accessibility) Cyan else Color(0xFFFFB454), fontSize = 13.sp,
                )
            }

            Spacer(Modifier.height(8.dp))
            BrainCard()

            if (recording != null) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onStopRecording,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE5484D)),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("■ Parar gravação de " + (if (recording == "tela") "tela" else "áudio")) }
            }

            Spacer(Modifier.height(12.dp))

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .border(1.dp, CyanDim, RoundedCornerShape(12.dp)).padding(12.dp),
            ) {
                items(log) { line ->
                    val mine = line.startsWith("Você:")
                    Text(
                        line, color = if (mine) Color.White else Cyan, fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 3.dp),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Também aceita comandos digitados (útil para testar).
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = typed, onValueChange = { typed = it }, singleLine = true,
                    placeholder = { Text("ou digite um comando…") }, modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = { if (typed.isNotBlank()) { onSend(typed); typed = "" } }) { Text("Enviar") }
            }
        }
    }
}

@Composable
private fun BrainCard() {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var key by remember { mutableStateOf(AiBrain.savedKey(ctx)) }
    var provider by remember { mutableStateOf(AiBrain.providerName(ctx)) }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Panel)
            .clickable { open = !open }.padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Cérebro (IA)", color = Color.White, modifier = Modifier.weight(1f))
            Text(
                if (provider.isNotEmpty()) "● $provider" else "○ Toque para configurar",
                color = if (provider.isNotEmpty()) Cyan else Color(0xFFFFB454), fontSize = 13.sp,
            )
        }
        if (open) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Cole uma chave gratuita do Google Gemini para o Jarvis responder qualquer pergunta, " +
                    "decidir sozinho o que fazer e enxergar pela câmera.",
                color = Color.Gray, fontSize = 12.sp,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = key, onValueChange = { key = it }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                placeholder = { Text("Chave (AIza...)") }, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row {
                OutlinedButton(onClick = {
                    ctx.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }) { Text("Pegar chave grátis") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    AiBrain.saveKey(ctx, key)
                    provider = AiBrain.providerName(ctx)
                    open = false
                    Jarvis.add("Jarvis: " + if (provider.isNotEmpty()) "Cérebro $provider conectado, senhor." else "Chave removida.")
                }) { Text("Salvar") }
            }
        }
    }
}
