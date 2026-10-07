package com.jarvis.assistant

import android.content.Context
import android.content.Intent
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Cérebro de IA do Jarvis.
 * - Padrão: servidor do Jarvis (Supabase), que guarda a chave do Gemini. O usuário não configura nada.
 * - Avançado: o usuário pode colar a própria chave (Gemini "AIza..." ou Claude "sk-ant...").
 */
object AiBrain {
    private const val PREFS = "jarvis"
    private const val PREF_KEY = "ai_key"
    private val GEMINI_MODELS = listOf("gemini-3.8-flash", "gemini-3.5-flash-lite")
    private const val CLAUDE_MODEL = "claude-haiku-5-5"
    private const val MAX_HISTORY = 6

    // Servidor do Jarvis. Esta chave é pública por natureza (identifica o projeto);
    // a chave da IA fica só no servidor.
    private const val CLOUD_URL = "https://jjxrckxksxodpelzvhsn.supabase.co/functions/v1/jarvis-ai"
    private const val CLOUD_ANON_KEY =
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6ImpqeHJja3hrc3hvZHBlbHp2aHNuIiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTE0MDY3NzIsImV4cCI6MjEwNjk4Mjc3Mn0.NM8Io6eBz0MxlUD9R6rvefO0ThFQV6EKnkR3hf3Umi0"

    private val history = mutableListOf<Pair<String, String>>()
    private var appNames: String? = null

    // ---------- chave ----------

    fun savedKey(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_KEY, "").orEmpty()

    fun saveKey(ctx: Context, key: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(PREF_KEY, key.trim()).apply()
    }

    private fun key(ctx: Context): String = savedKey(ctx).ifBlank { BuildConfig.CLAUDE_API_KEY }

    fun hasKey(ctx: Context) = true // o servidor do Jarvis sempre está disponível

    fun providerName(ctx: Context): String = when {
        key(ctx).isBlank() -> "Jarvis Cloud"
        key(ctx).startsWith("sk-ant") -> "Claude (sua chave)"
        else -> "Gemini (sua chave)"
    }

    private fun deviceId(ctx: Context): String {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString("device_id", null) ?: java.util.UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }
    }

    // ---------- comandos e conversa ----------

    suspend fun interpret(ctx: Context, text: String): Command = withContext(Dispatchers.IO) {
        val key = key(ctx)
        val system = systemPrompt(ctx)
        val result = if (key.startsWith("sk-ant")) askClaude(key, system, text) else askGemini(ctx, key, system, text)
        result.fold(
            onSuccess = { raw ->
                remember(text, raw)
                parseCommand(raw)
            },
            onFailure = { e -> Command("responder", mapOf("texto" to (e.message ?: "Falha na IA."))) },
        )
    }

    /** Recebe uma foto (JPEG) e responde a pergunta sobre ela. */
    suspend fun describeImage(ctx: Context, jpeg: ByteArray, question: String): String = withContext(Dispatchers.IO) {
        val key = key(ctx)
        val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        val system = "Você é o J.A.R.V.I.S. Responda em português do Brasil, em no máximo 3 frases curtas, " +
            "sem markdown, como quem fala em voz alta. Trate o usuário por senhor."
        val result = if (key.startsWith("sk-ant")) {
            val content = JSONArray()
                .put(JSONObject().put("type", "image").put("source", JSONObject()
                    .put("type", "base64").put("media_type", "image/jpeg").put("data", b64)))
                .put(JSONObject().put("type", "text").put("text", question))
            claudeCall(key, system, JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        } else {
            val parts = JSONArray()
                .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", b64)))
                .put(JSONObject().put("text", question))
            gemini(ctx, key, system, JSONArray().put(JSONObject().put("role", "user").put("parts", parts)), json = false)
        }
        result.getOrElse { it.message ?: "Não consegui analisar a imagem." }
            .replace(Regex("[*#_`]"), "").trim()
    }

    // ---------- provedores ----------

    private fun askGemini(ctx: Context, key: String, system: String, text: String): Result<String> {
        val contents = JSONArray()
        synchronized(history) {
            history.forEach { (u, a) -> contents.put(geminiMsg("user", u)); contents.put(geminiMsg("model", a)) }
        }
        contents.put(geminiMsg("user", text))
        return gemini(ctx, key, system, contents, json = true)
    }

    /** Sem chave própria → servidor do Jarvis; com chave → direto no Google. */
    private fun gemini(ctx: Context, key: String, system: String, contents: JSONArray, json: Boolean): Result<String> =
        if (key.isBlank()) cloudCall(ctx, system, contents, json) else geminiCall(key, system, contents, json)

    private fun cloudCall(ctx: Context, system: String, contents: JSONArray, json: Boolean): Result<String> {
        val body = JSONObject()
            .put("device", deviceId(ctx))
            .put("system", system)
            .put("contents", contents)
            .put("json", json)
        val (code, resp) = try {
            post(CLOUD_URL, mapOf("Authorization" to "Bearer $CLOUD_ANON_KEY", "apikey" to CLOUD_ANON_KEY), body)
        } catch (e: Exception) {
            return Result.failure(Exception("Estou sem conexão com a internet, senhor."))
        }
        val obj = runCatching { JSONObject(resp) }.getOrNull()
        if (code in 200..299 && obj != null && obj.has("text")) return Result.success(obj.getString("text"))
        return Result.failure(Exception(obj?.optString("error")?.ifBlank { null } ?: "O servidor do Jarvis não respondeu ($code)."))
    }

    private fun geminiMsg(role: String, t: String) =
        JSONObject().put("role", role).put("parts", JSONArray().put(JSONObject().put("text", t)))

    private fun geminiCall(key: String, system: String, contents: JSONArray, json: Boolean): Result<String> {
        val config = JSONObject().put("temperature", 0.5).put("maxOutputTokens", 2048)
        if (json) config.put("responseMimeType", "application/json")
        val body = JSONObject()
            .put("system_instruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents)
            .put("generationConfig", config)
        var lastError = "A IA não respondeu, senhor."
        for (model in GEMINI_MODELS) {
            val (code, resp) = try {
                post(
                    "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent",
                    mapOf("x-goog-api-key" to key), body,
                )
            } catch (e: Exception) {
                return Result.failure(Exception("Estou sem conexão com a internet, senhor."))
            }
            if (code in 200..299) {
                return runCatching {
                    val parts = JSONObject(resp).getJSONArray("candidates").getJSONObject(0)
                        .getJSONObject("content").getJSONArray("parts")
                    buildString {
                        for (i in 0 until parts.length()) {
                            val p = parts.getJSONObject(i)
                            if (!p.optBoolean("thought")) append(p.optString("text"))
                        }
                    }
                }
            }
            lastError = when {
                code == 429 -> "Atingi o limite gratuito da IA por enquanto, senhor. Tente de novo em alguns minutos."
                code == 400 && resp.contains("API_KEY", ignoreCase = true) -> "A chave do Gemini parece inválida, senhor."
                code == 403 -> "A chave do Gemini não tem permissão, senhor."
                else -> "A IA retornou um erro $code."
            }
            if (code != 429 && code < 500 && code != 404) break // erro que outro modelo não resolve
        }
        return Result.failure(Exception(lastError))
    }

    private fun askClaude(key: String, system: String, text: String): Result<String> {
        val messages = JSONArray()
        synchronized(history) {
            history.forEach { (u, a) ->
                messages.put(JSONObject().put("role", "user").put("content", u))
                messages.put(JSONObject().put("role", "assistant").put("content", a))
            }
        }
        messages.put(JSONObject().put("role", "user").put("content", text))
        return claudeCall(key, system, messages)
    }

    private fun claudeCall(key: String, system: String, messages: JSONArray): Result<String> {
        val body = JSONObject().put("model", CLAUDE_MODEL).put("max_tokens", 800)
            .put("system", system).put("messages", messages)
        val (code, resp) = try {
            post(
                "https://api.anthropic.com/v1/messages",
                mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"), body,
            )
        } catch (e: Exception) {
            return Result.failure(Exception("Estou sem conexão com a internet, senhor."))
        }
        if (code !in 200..299) return Result.failure(Exception("A IA retornou um erro $code."))
        return runCatching { JSONObject(resp).getJSONArray("content").getJSONObject(0).getString("text") }
    }

    private fun post(url: String, headers: Map<String, String>, body: JSONObject): Pair<Int, String> {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 45_000
            setRequestProperty("content-type", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        return code to text
    }

    // ---------- utilidades ----------

    private fun remember(user: String, assistant: String) = synchronized(history) {
        history += user to assistant
        while (history.size > MAX_HISTORY) history.removeAt(0)
    }

    private fun parseCommand(raw: String): Command {
        return try {
            val json = JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1))
            val params = json.optJSONObject("params")
            val map = mutableMapOf<String, String>()
            params?.keys()?.forEach { k -> map[k] = params.optString(k) }
            Command(json.getString("action"), map)
        } catch (e: Exception) {
            Command("responder", mapOf("texto" to raw.replace(Regex("[*#_`{}]"), "").trim()))
        }
    }

    private fun installedApps(ctx: Context): String {
        appNames?.let { return it }
        val pm = ctx.packageManager
        val names = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.loadLabel(pm).toString() }.distinct().sorted().take(200).joinToString(", ")
        appNames = names
        return names
    }

    private fun systemPrompt(ctx: Context): String {
        val now = SimpleDateFormat("EEEE, dd 'de' MMMM 'de' yyyy, HH:mm", Locale("pt", "BR")).format(Date())
        return """
            Você é o J.A.R.V.I.S., o assistente pessoal de voz (como no filme Homem de Ferro) num celular Android.
            Fale português do Brasil, trate o usuário por "senhor", seja educado, levemente espirituoso e BREVE:
            suas respostas são faladas em voz alta, então use no máximo 3 frases, sem markdown, emojis ou listas
            (só se estenda se o usuário pedir explicação detalhada).
            Data e hora atuais: $now.

            Responda SEMPRE e SOMENTE com JSON no formato {"action": "...", "params": {...}}.
            Ações disponíveis:
            - responder {texto}: perguntas, conversa, explicações, contas, traduções, conselhos, piadas.
            - abrir_app {nome}: use o nome exato de um dos apps instalados listados abaixo.
            - pesquisar_google {termo}: notícias, placares, clima, preços e qualquer fato recente ou que você não saiba com certeza.
            - youtube_pesquisar {termo}
            - whatsapp_mensagem {contato, texto}
            - ligar {contato}
            - digitar {texto}: escrever no campo de texto aberto na tela.
            - tirar_foto {camera: "traseira" ou "frontal"} (selfie = frontal)
            - descrever_foto {pergunta}: quando pedirem para você olhar, ver, ler ou identificar algo pela câmera.
            - abrir_camera {}, filmar {}
            - gravar_audio {}, gravar_tela {}, parar_gravacao {}
            - lanterna {estado: "on" ou "off"}
            - voltar {}, home {}, hora {}
            Nunca invente fatos recentes: se não tiver certeza, use pesquisar_google.

            Apps instalados: ${installedApps(ctx)}
        """.trimIndent()
    }
}
