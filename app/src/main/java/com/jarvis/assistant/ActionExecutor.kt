package com.jarvis.assistant

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.util.Calendar

/** Executa um Command e devolve a frase que o Jarvis vai falar. */
class ActionExecutor(private val ctx: Context) {

    fun execute(cmd: Command): String {
        val p = cmd.params
        return try {
            when (cmd.action) {
                "abrir_app" -> openApp(p["nome"].orEmpty())
                "youtube_pesquisar" -> youtube(p["termo"].orEmpty())
                "pesquisar_google" -> google(p["termo"].orEmpty())
                "whatsapp_mensagem" -> whatsapp(p["contato"].orEmpty(), p["texto"].orEmpty())
                "digitar" -> type(p["texto"].orEmpty())
                "ligar" -> call(p["contato"].orEmpty())
                "lanterna" -> torch(p["estado"] == "on")
                "gravar_audio" -> Recorders.startAudio(ctx)
                "parar_gravacao" -> Recorders.stopAll()
                "gravar_tela" -> {
                    launch(Intent(ctx, MainActivity::class.java).setAction(MainActivity.ACTION_SCREEN_RECORD))
                    "Autorize a gravação da tela, senhor."
                }
                "abrir_camera" -> {
                    launch(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
                    "Abrindo a câmera."
                }
                "filmar" -> {
                    launch(Intent(MediaStore.INTENT_ACTION_VIDEO_CAMERA))
                    "Câmera de vídeo pronta, senhor."
                }
                "tirar_foto" -> {
                    launch(
                        Intent(ctx, CaptureActivity::class.java)
                            .putExtra(CaptureActivity.EXTRA_FRONT, p["camera"] == "frontal")
                    )
                    ""
                }
                "descrever_foto" -> {
                    if (!AiBrain.hasKey(ctx)) return "Para eu enxergar, configure a chave de IA no aplicativo, senhor."
                    launch(
                        Intent(ctx, CaptureActivity::class.java)
                            .putExtra(CaptureActivity.EXTRA_DESCRIBE, true)
                            .putExtra(CaptureActivity.EXTRA_QUESTION, p["pergunta"].orEmpty().ifBlank { "O que você está vendo?" })
                    )
                    "Deixe-me ver."
                }
                "voltar" -> global(AccessibilityService.GLOBAL_ACTION_BACK, "Voltando.")
                "home" -> global(AccessibilityService.GLOBAL_ACTION_HOME, "Tela inicial.")
                "hora" -> {
                    val c = Calendar.getInstance()
                    "Agora são ${c.get(Calendar.HOUR_OF_DAY)} horas e ${c.get(Calendar.MINUTE)} minutos."
                }
                "responder" -> p["texto"].orEmpty().ifBlank { "Pois não." }
                "parar_escuta" -> {
                    ctx.stopService(Intent(ctx, JarvisService::class.java))
                    "Modo de escuta desativado. Até logo, senhor."
                }
                else -> "Desculpe, ainda não sei fazer isso."
            }
        } catch (e: Exception) {
            "Algo deu errado: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun launch(i: Intent) = ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    private fun openApp(name: String): String {
        if (name.isBlank()) return "Qual aplicativo, senhor?"
        val pm = ctx.packageManager
        val target = CommandParser.norm(name)
        val apps = pm.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
        )
        var bestPkg: String? = null
        var bestLabel = ""
        var bestScore = 0
        for (info in apps) {
            val label = info.loadLabel(pm).toString()
            val l = CommandParser.norm(label)
            val score = when {
                l == target -> 4
                l.startsWith(target) -> 3
                l.contains(target) -> 2
                target.contains(l) && l.length > 2 -> 1
                else -> 0
            }
            if (score > bestScore) {
                bestScore = score; bestPkg = info.activityInfo.packageName; bestLabel = label
            }
        }
        val launchIntent = bestPkg?.let { pm.getLaunchIntentForPackage(it) }
            ?: return "Não encontrei o aplicativo $name."
        launch(launchIntent)
        return "Abrindo $bestLabel."
    }

    private fun youtube(q: String): String {
        try {
            launch(Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube").putExtra("query", q))
        } catch (e: ActivityNotFoundException) {
            launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))))
        }
        return "Procurando $q no YouTube."
    }

    private fun google(q: String): String {
        try {
            launch(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q))
        } catch (e: ActivityNotFoundException) {
            launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))))
        }
        return "Pesquisando $q."
    }

    private fun whatsapp(contact: String, text: String): String {
        val raw = findNumber(contact) ?: return "Não encontrei $contact nos seus contatos."
        var digits = raw.filter { it.isDigit() }.trimStart('0')
        if (digits.length <= 11) digits = "55$digits" // número brasileiro sem DDI
        val uri = Uri.parse("https://api.whatsapp.com/send?phone=$digits&text=${Uri.encode(text)}")
        val autoSend = JarvisAccessibilityService.instance != null
        if (autoSend) JarvisAccessibilityService.pendingSendUntil = System.currentTimeMillis() + 12_000
        try {
            launch(Intent(Intent.ACTION_VIEW, uri).setPackage("com.whatsapp"))
        } catch (e: ActivityNotFoundException) {
            try {
                launch(Intent(Intent.ACTION_VIEW, uri).setPackage("com.whatsapp.w4b"))
            } catch (e2: ActivityNotFoundException) {
                JarvisAccessibilityService.pendingSendUntil = 0
                return "O WhatsApp não está instalado."
            }
        }
        return if (autoSend) "Enviando mensagem para $contact."
        else "Mensagem pronta. Ative a acessibilidade para eu enviar sozinho."
    }

    private fun type(text: String): String {
        val svc = JarvisAccessibilityService.instance
            ?: return "Preciso da permissão de acessibilidade para digitar."
        return if (svc.typeText(text)) "Digitado." else "Não encontrei um campo de texto na tela."
    }

    private fun call(contact: String): String {
        val number = findNumber(contact) ?: return "Não encontrei $contact nos seus contatos."
        val canCall = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
        launch(Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:$number")))
        return "Ligando para $contact."
    }

    private fun torch(on: Boolean): String {
        val cm = ctx.getSystemService(CameraManager::class.java) ?: return "Câmera indisponível."
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "Este aparelho não tem lanterna."
        cm.setTorchMode(id, on)
        return if (on) "Lanterna ligada." else "Lanterna desligada."
    }

    private fun global(action: Int, reply: String): String {
        val svc = JarvisAccessibilityService.instance
            ?: return "Preciso da permissão de acessibilidade para isso."
        svc.performGlobalAction(action)
        return reply
    }

    /** Procura o telefone de um contato pelo nome (ou aceita um número falado). */
    private fun findNumber(name: String): String? {
        val spokenDigits = name.filter { it.isDigit() }
        if (spokenDigits.length >= 8) return spokenDigits
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) return null
        val target = CommandParser.norm(name)
        var best: String? = null
        var bestScore = 0
        ctx.contentResolver.query(
            Phone.CONTENT_URI, arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER), null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val display = c.getString(0) ?: continue
                val n = CommandParser.norm(display)
                val score = when {
                    n == target -> 3
                    n.startsWith(target) -> 2
                    n.contains(target) -> 1
                    else -> 0
                }
                if (score > bestScore) {
                    bestScore = score; best = c.getString(1)
                }
            }
        }
        return best
    }
}
