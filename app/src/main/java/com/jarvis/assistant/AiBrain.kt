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
 * - Chave do Google Gemini (grátis em aistudio.google.com/apikey) → usa Gemini.
 * - Chave começando com "sk-ant" → usa Claude.
 */
object AiBrain {
    private const val PREFS = "jarvis"
    private const val PREF_KEY = "ai_key"
    private val GEMINI_MODELS = listOf("gemini-3.8-flash", "gemini-3.5-flash-lite")
    private const val CLAUDE_MODEL = "claude-haiku-5-5"
    private const val MAX_HISTORY = 6

    private val history = mutableListOf<Pair<String, String>>()
    private var appNames: String? = null

    // ---------- chave ----------

    fun savedKey(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_KEY, "").orEmpty()

    fun saveKey(ctx: Context, key: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(PREF_KEY, key.trim()).apply()
    }

    private fun key(ctx: Context): String = savedKey(ctx).ifBlank { BuildConfig.CLAUDE_API_KEY }

    fun hasKey(ctx: Context) = key(ctx).isNotBlank()

    fun providerName(ctx: Context): String = when {
        !hasKey(ctx) -> ""
        key(ctx).startsWith("sk-ant") -> "Claude"
        else -> "Gemini"
    }

    // ---------- comandos e conversa ----------

    suspend fun interpret(ctx: Context, text: String): Command = withContext(Dispatchers.IO) {
        val key = key(ctx)
        if (key.isBlank()) {
            return@withContext Command(
                "responder",
                mapOf("texto" to "Para responder isso eu preciso do meu cérebro de IA, senhor. Toque em Cérebro no aplicativo e cole uma chave gratuita do Gemini."),
            )
        }
        val system = systemPrompt(ctx)
        val result = if (key.startsWith("sk-ant")) askClaude(key, system, text) else askGemini(key, system, text)
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
        if (key.isBlank()) return@withContext "Para eu enxergar, configure a chave de IA no aplicativo, senhor."
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
            geminiCall(key, system, JSONArray().put(JSONObject().put("role", "user").put("parts", parts)), json = false)
        }
        result.getOrElse { it.message ?: "Não consegui analisar a imagem." }
            .replace(Regex("[*#_`]"), "").trim()
    }

    // ---------- provedores ----------

    private fun askGemini(key: String, system: String, text: String): Result<String> {
        val contents = JSONArray()
        synchronized(history) {
            history.forEach { (u, a) -> contents.put(geminiMsg("user", u)); contents.put(geminiMsg("model", a)) }
        }
        contents.put(geminiMsg("user", text))
        return geminiCall(key, system, contents, json = true)
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
