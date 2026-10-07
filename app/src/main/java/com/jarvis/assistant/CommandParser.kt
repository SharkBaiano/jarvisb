package com.jarvis.assistant

import java.text.Normalizer

data class Command(val action: String, val params: Map<String, String> = emptyMap())

/** Interpreta comandos em português com regras locais (rápido, offline, sem custo). */
object CommandParser {

    private fun r(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private fun cmd(action: String, vararg p: Pair<String, String>) = Command(action, mapOf(*p))

    // A ordem importa: regras mais específicas primeiro.
    private val rules: List<Pair<Regex, (MatchResult) -> Command>> = listOf(
        // Gravações
        r("""^(?:pare|parar|para|finalize|termine)\s+(?:a\s+|de\s+)?grava\S*""") to { _ -> cmd("parar_gravacao") },
        r("""grav\S*\s+(?:a\s+)?tela""") to { _ -> cmd("gravar_tela") },
        r("""^(?:come[cç]\S*|inici\S*)?\s*(?:a\s+)?grav\S*(?:\s+(?:de\s+|um\s+|o\s+)?(?:[aá]udio|voz))?\s*$""") to { _ -> cmd("gravar_audio") },

        // Lanterna
        r("""(?:deslig|apag)\S*\s+(?:a\s+)?lanterna""") to { _ -> cmd("lanterna", "estado" to "off") },
        r("""(?:lig|acend)\S*\s+(?:a\s+)?lanterna""") to { _ -> cmd("lanterna", "estado" to "on") },

        // WhatsApp: "manda mensagem pra Maria dizendo oi"
        r("""(?:mand\S*|envi\S*|escrev\S*)\s+(?:uma\s+)?(?:mensagem\s+|msg\s+)?(?:no\s+whats\S*\s+)?(?:para|pra|pro)\s+(?:o\s+|a\s+)?(.+?)\s+(?:no\s+whats\S*\s+)?(?:dizendo|falando|escrito|com\s+o\s+texto|que)\s+(.+)""") to { m ->
            cmd("whatsapp_mensagem", "contato" to m.groupValues[1].trim(), "texto" to m.groupValues[2].trim())
        },

        // YouTube
        r("""(?:pesquis\S*|procur\S*|busc\S*|toc\S*|coloc\S*|bot\S*)\s+(?:o\s+v[ií]deo\s+|v[ií]deo\s+)?(?:de\s+)?(.+?)\s+no\s+youtube""") to { m ->
            cmd("youtube_pesquisar", "termo" to m.groupValues[1].trim())
        },
        r("""youtube\s+(?:e\s+)?(?:pesquis\S*|procur\S*|busc\S*|toc\S*|coloc\S*)\s+(?:por\s+)?(.+)""") to { m ->
            cmd("youtube_pesquisar", "termo" to m.groupValues[1].trim())
        },

        // Ligação
        r("""^(?:ligu\S*|ligar|liga|telefon\S*)\s+(?:para|pra|pro)\s+(?:o\s+|a\s+)?(.+)""") to { m ->
            cmd("ligar", "contato" to m.groupValues[1].trim())
        },

        // Digitar no campo aberto
        r("""^(?:digit\S*|escrev\S*)\s+(.+)""") to { m -> cmd("digitar", "texto" to m.groupValues[1].trim()) },

        // Google
        r("""^(?:pesquis\S*|procur\S*|busc\S*)\s+(?:no\s+google\s+)?(?:por\s+|sobre\s+)?(.+)""") to { m ->
            cmd("pesquisar_google", "termo" to m.groupValues[1].trim())
        },

        // Sistema
        r("""(?:pare\s+de\s+(?:ouvir|escutar)|desativ\S*|durma|dormir)""") to { _ -> cmd("parar_escuta") },
        r("""que\s+horas""") to { _ -> cmd("hora") },
        r("""tela\s+inicial""") to { _ -> cmd("home") },
        r("""^volt\S*$""") to { _ -> cmd("voltar") },

        // Abrir app (genérico, por último)
        r("""^(?:abr\S*|inici\S*|execut\S*|entr\S*\s+no)\s+(?:o\s+|a\s+)?(?:aplicativo\s+|app\s+)?(?:do\s+|da\s+)?(.+)""") to { m ->
            cmd("abrir_app", "nome" to m.groupValues[1].trim())
        },
    )

    fun parse(raw: String): Command? {
        val text = raw.trim()
            .replace(Regex("""^(?:ei\s+)?jarvis[,!.]?\s*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*,?\s*por\s+favor""", RegexOption.IGNORE_CASE), "")
            .trimEnd('.', '!', '?', ' ')
        if (text.isEmpty()) return null
        for ((regex, build) in rules) {
            val m = regex.find(text) ?: continue
            return build(m)
        }
        return null
    }

    /** Minúsculas e sem acentos, para comparar nomes. */
    fun norm(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase().trim()
}
