package com.jarvis.assistant

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.io.File

/** Grava a tela em MP4 (pasta Movies do app). */
class ScreenRecordService : Service() {

    companion object {
        @Volatile var instance: ScreenRecordService? = null
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var stopped = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        val n = Notifications.build(this, "Gravando a tela")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(2, n)
        }
        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data = intent?.let { readData(it) }
        if (data == null) {
            stopRecording()
            return START_NOT_STICKY
        }
        try {
            start(code, data)
        } catch (e: Exception) {
            Jarvis.add("Jarvis: falha ao gravar a tela (${e.message})")
            stopRecording()
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun readData(i: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        else i.getParcelableExtra(EXTRA_DATA)

    private fun start(code: Int, data: Intent) {
        val mpm = getSystemService(MediaProjectionManager::class.java)!!
        val proj = mpm.getMediaProjection(code, data) ?: error("sem permissão")
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() = stopRecording()
        }, Handler(Looper.getMainLooper()))

        val m = resources.displayMetrics
        val scale = minOf(1f, 720f / m.widthPixels)
        val w = ((m.widthPixels * scale).toInt() / 2) * 2
        val h = ((m.heightPixels * scale).toInt() / 2) * 2
        val file = File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "jarvis_${Recorders.stamp()}.mp4")

        val r = Recorders.newRecorder(this).apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoSize(w, h)
            setVideoFrameRate(30)
            setVideoEncodingBitRate(6_000_000)
            setOutputFile(file.absolutePath)
            prepare()
        }
        display = proj.createVirtualDisplay(
            "jarvis", w, h, m.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null
        )
        r.start()
        projection = proj
        recorder = r
        Recorders.recording.value = "tela"
    }

    fun stopRecording() {
        if (stopped) return
        stopped = true
        recorder?.let { runCatching { it.stop() }; it.release() }
        recorder = null
        display?.release()
        display = null
        projection?.let { runCatching { it.stop() } }
        projection = null
        Recorders.recording.value = null
        instance = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }
}
