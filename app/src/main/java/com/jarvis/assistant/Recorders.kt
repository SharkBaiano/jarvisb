package com.jarvis.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Gravação de áudio e controle do estado das gravações. */
object Recorders {
    /** "audio", "tela" ou null. */
    val recording = MutableStateFlow<String?>(null)
    private var audio: MediaRecorder? = null

    fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    @Suppress("DEPRECATION")
    fun newRecorder(ctx: Context): MediaRecorder =
        if (Build.VERSION.SDK_INT >= 31) MediaRecorder(ctx) else MediaRecorder()

    fun startAudio(ctx: Context): String {
        if (recording.value != null) return "Já estou gravando, senhor."
        val file = File(ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "jarvis_${stamp()}.m4a")
        val r = newRecorder(ctx).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(128_000)
            setAudioSamplingRate(44_100)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }
        audio = r
        recording.value = "audio"
        return "Gravando áudio."
    }

    fun stopAll(): String {
        audio?.let {
            runCatching { it.stop() }
            it.release()
            audio = null
            recording.value = null
            return "Gravação de áudio salva."
        }
        ScreenRecordService.instance?.let {
            it.stopRecording()
            return "Gravação de tela salva."
        }
        recording.value = null
        return "Nada está sendo gravado."
    }
}

object Notifications {
    private const val CHANNEL = "jarvis"

    fun build(ctx: Context, text: String): Notification {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm?.createNotificationChannel(
            NotificationChannel(CHANNEL, "Jarvis", NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(ctx, CHANNEL)
            .setContentTitle("J.A.R.V.I.S.")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }
}
