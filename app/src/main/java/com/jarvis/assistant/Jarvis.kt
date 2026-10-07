package com.jarvis.assistant

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

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
            val cmd = CommandParser.parse(text) ?: AiBrain.interpret(app, text)
            val reply = ActionExecutor(app).execute(cmd)
            if (reply.isNotBlank()) add("Jarvis: $reply")
            status.value = "Pronto, senhor."
            speaker(app).say(reply, onFinished)
        }
    }
}
