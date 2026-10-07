package com.jarvis.assistant

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow

/** Escuta contínua em segundo plano: reage quando ouve "Jarvis". */
class JarvisService : Service() {

    companion object {
        val running = MutableStateFlow(false)
        private val WAKE = Regex("""\b(?:jarvis|j[aá]rvis|jarves|jervis|djarvis)\b""", RegexOption.IGNORE_CASE)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var listener: VoiceListener
    private var awaitingCommand = false
    private var active = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val n = Notifications.build(this, "Ouvindo... diga \"Jarvis\"")
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, n)
        }
        listener = VoiceListener(this)
        Jarvis.speaker(this)
        running.value = true
        Jarvis.status.value = "Aguardando \"Jarvis\"..."
        listenLoop(500)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun listenLoop(delay: Long = 250) {
        handler.postDelayed({
            if (!active) return@postDelayed
            // O microfone fica livre enquanto há uma gravação de áudio em andamento.
            if (Recorders.recording.value == "audio") {
                listenLoop(1500)
                return@postDelayed
            }
            listener.listen(
                onResult = { onHeard(it) },
                onFail = { awaitingCommand = false; listenLoop() },
                onLevel = { Jarvis.level.value = it },
            )
        }, delay)
    }

    private fun onHeard(text: String) {
        if (awaitingCommand) {
            awaitingCommand = false
            Jarvis.handle(this, text, scope) { listenLoop() }
            return
        }
        val m = WAKE.find(text) ?: return listenLoop()
        val rest = text.substring(m.range.last + 1).trim(' ', ',', '.', '!', '?')
        if (rest.isEmpty()) {
            awaitingCommand = true
            Jarvis.speaker(this).say("Pois não, senhor?") { listenLoop(100) }
        } else {
            Jarvis.handle(this, rest, scope) { listenLoop() }
        }
    }

    override fun onDestroy() {
        active = false
        handler.removeCallbacksAndMessages(null)
        listener.destroy()
        scope.cancel()
        running.value = false
        Jarvis.status.value = "Pronto, senhor."
        super.onDestroy()
    }
}
