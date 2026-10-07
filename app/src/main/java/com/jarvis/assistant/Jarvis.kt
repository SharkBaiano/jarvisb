package com.jarvis.assistant

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Cérebro: recebe o texto falado, entende, executa e responde. */
object Jarvis {
    val log = MutableStateFlow<List<String>>(emptyList())
    val status = MutableStateFlow("Pronto, senhor.")
    val level = MutableStateFlow(0f)

    private var speaker: Speaker? = null

    fun speaker(ctx: Context): Speaker =
        speaker ?: Speaker(ctx.applicationContext).also { speaker = it }

    fun add(line: String) {
        log.value = (log.value + line).takeLast(40)
    }

    fun handle(ctx: Context, text: String, scope: CoroutineScope, onFinished: () -> Unit = {}) {
        val app = ctx.applicationContext
        add("Você: $text")
        status.value = "Processando..."
        scope.launch {
            val cmd = CommandParser.parse(text) ?: ClaudeFallback.interpret(text)
            val reply = if (cmd == null) {
                "Desculpe, senhor, não entendi."
            } else {
                ActionExecutor(app).execute(cmd)
            }
            add("Jarvis: $reply")
            status.value = "Pronto, senhor."
            speaker(app).say(reply, onFinished)
        }
    }
}

/** Quando nenhuma regra local entende o pedido, pergunta à Claude (opcional). */
object ClaudeFallback {
    private const val MODEL = "claude-haiku-5-5"

    private val SYSTEM = """
        Você é o Jarvis, assistente de voz de um celular Android. Converta o pedido do usuário
        em UMA ação, respondendo só com JSON: {"action": "...", "params": {...}}.
        Ações: abrir_app{nome}, youtube_pesquisar{termo}, pesquisar_google{termo},
        whatsapp_mensagem{contato,texto}, digitar{texto}, ligar{contato}, lanterna{estado:on|off},
        gravar_audio{}, gravar_tela{}, parar_gravacao{}, voltar{}, home{}, hora{},
        responder{texto} (para perguntas ou conversa; resposta curta, educada, estilo Jarvis).
    """.trimIndent()

    suspend fun interpret(text: String): Command? = withContext(Dispatchers.IO) {
        val key = BuildConfig.CLAUDE_API_KEY
        if (key.isBlank()) return@withContext null
        runCatching {
            val body = JSONObject()
                .put("model", MODEL)
                .put("max_tokens", 300)
                .put("system", SYSTEM)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", text)))
            val conn = (URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 10_000
                readTimeout = 20_000
                setRequestProperty("x-api-key", key)
                setRequestProperty("anthropic-version", "2023-06-01")
                setRequestProperty("content-type", "application/json")
            }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val resp = conn.inputStream.bufferedReader().use { it.readText() }
            val out = JSONObject(resp).getJSONArray("content").getJSONObject(0).getString("text")
            val json = JSONObject(out.substring(out.indexOf('{'), out.lastIndexOf('}') + 1))
            val params = json.optJSONObject("params")
            val map = mutableMapOf<String, String>()
            params?.keys()?.forEach { k -> map[k] = params.optString(k) }
            Command(json.getString("action"), map)
        }.getOrNull()
    }
}
