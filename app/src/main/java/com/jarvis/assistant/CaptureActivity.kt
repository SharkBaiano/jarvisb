package com.jarvis.assistant

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Tira uma foto (com contagem) ou captura uma imagem para a IA descrever. */
class CaptureActivity : ComponentActivity() {

    companion object {
        const val EXTRA_FRONT = "front"
        const val EXTRA_DESCRIBE = "describe"
        const val EXTRA_QUESTION = "question"
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var preview: PreviewView
    private lateinit var label: TextView
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preview = PreviewView(this)
        label = TextView(this).apply {
            setTextColor(0xFF38E1FF.toInt())
            textSize = 64f
            gravity = Gravity.CENTER
            setShadowLayer(12f, 0f, 0f, Color.BLACK)
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(preview, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(label, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        })

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            finishWith("Preciso da permissão da câmera, senhor. Abra o Jarvis e permita.")
            return
        }

        val front = intent.getBooleanExtra(EXTRA_FRONT, false)
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val previewUseCase = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                provider.unbindAll()
                provider.bindToLifecycle(this, selector, previewUseCase, capture)
                start(capture)
            } catch (e: Exception) {
                finishWith("Não consegui abrir a câmera, senhor.")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun start(capture: ImageCapture) {
        if (intent.getBooleanExtra(EXTRA_DESCRIBE, false)) {
            label.textSize = 28f
            label.text = "Observando..."
            handler.postDelayed({ captureForAi(capture) }, 1500)
        } else {
            Jarvis.speaker(this).say("Sorria, senhor.")
            listOf("3", "2", "1").forEachIndexed { i, s -> handler.postDelayed({ label.text = s }, 900L + i * 800L) }
            handler.postDelayed({ label.text = ""; savePhoto(capture) }, 900L + 3 * 800L)
        }
    }

    private fun savePhoto(capture: ImageCapture) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "jarvis_${Recorders.stamp()}")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/Jarvis")
        }
        val options = ImageCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ).build()
        capture.takePicture(options, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                preview.foreground = android.graphics.drawable.ColorDrawable(Color.WHITE)
                handler.postDelayed({ finishWith("Foto salva na galeria, senhor.") }, 250)
            }
            override fun onError(exception: ImageCaptureException) =
                finishWith("Não consegui tirar a foto, senhor.")
        })
    }

    private fun captureForAi(capture: ImageCapture) {
        capture.takePicture(ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                val rotation = image.imageInfo.rotationDegrees
                image.close()
                label.text = "Analisando..."
                val question = intent.getStringExtra(EXTRA_QUESTION) ?: "O que você está vendo?"
                lifecycleScope.launch {
                    val jpeg = withContext(Dispatchers.Default) { shrink(bytes, rotation) }
                    finishWith(AiBrain.describeImage(this@CaptureActivity, jpeg, question))
                }
            }
            override fun onError(exception: ImageCaptureException) =
                finishWith("Não consegui usar a câmera, senhor.")
        })
    }

    /** Reduz a foto para no máximo ~1024px (envio rápido para a IA). */
    private fun shrink(bytes: ByteArray, rotation: Int): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1024) sample *= 2
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return bytes
        if (rotation != 0) {
            bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
        }
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
    }

    private fun finishWith(message: String) {
        if (done) return
        done = true
        Jarvis.add("Jarvis: $message")
        Jarvis.speaker(this).say(message)
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
