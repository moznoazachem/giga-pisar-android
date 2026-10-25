package ru.gigapisar.brain

/**
 * Known cloud services for the Brain. People have a key and the name of the service, not
 * its API address, so the app guesses the service from the key and fills in the address
 * and a sensible model. Same list as the desktop apps.
 */
data class BrainProvider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val preferredModels: List<String>,
    val keysUrl: String,
    /** Local servers take no key; the address the person typed is the whole setup. */
    val local: Boolean = false,
)

object BrainProviders {
    val DeepSeek =
        BrainProvider(
            "deepseek",
            "DeepSeek",
            "https://api.deepseek.com/v1",
            listOf("deepseek-flash", "deepseek-chat"),
            "https://platform.deepseek.com/api_keys",
        )
    val OpenRouter =
        BrainProvider(
            "openrouter",
            "OpenRouter",
            "https://openrouter.ai/api/v1",
            listOf("deepseek/deepseek-chat-v3-0324", "deepseek/deepseek-chat", "google/gemini-2.5-flash", "openai/gpt-4.1-mini"),
            "https://openrouter.ai/keys",
        )
    val OpenAI =
        BrainProvider(
            "openai",
            "OpenAI",
            "https://api.openai.com/v1",
            listOf("gpt-4.1-mini", "gpt-4o-mini"),
            "https://platform.openai.com/api-keys",
        )
    val Groq =
        BrainProvider(
            "groq",
            "Groq",
            "https://api.groq.com/openai/v1",
            listOf("llama-3.3-70b-versatile"),
            "https://console.groq.com/keys",
        )
    val Gemini =
        BrainProvider(
            "gemini",
            "Google Gemini",
            "https://generativelanguage.googleapis.com/v1beta/openai",
            listOf("gemini-2.5-flash", "gemini-2.0-flash"),
            "https://aistudio.google.com/apikey",
        )
    val Anthropic =
        BrainProvider(
            "anthropic",
            "Anthropic (Claude)",
            "https://api.anthropic.com/v1",
            listOf("claude-haiku-4-5"),
            "https://console.anthropic.com/settings/keys",
        )

    /** The local server is configured on the "Local server" tab; baseUrl is replaced at calls. */
    val Ollama =
        BrainProvider(
            "ollama",
            "Ollama",
            "http://0.0.0.0:11434/v1",
            listOf("llama3.2"),
            "https://ollama.com",
            local = true,
        )

    val all = listOf(DeepSeek, OpenRouter, OpenAI, Groq, Gemini, Anthropic, Ollama)
    val cloud = listOf(DeepSeek, OpenRouter, OpenAI, Groq, Gemini, Anthropic)
    val localServers = listOf(Ollama)

    fun byId(id: String?): BrainProvider? = all.firstOrNull { it.id == id }

    private val deepSeekKey = Regex("^sk-[0-9a-f]{32}$")

    /** Guesses the service from the look of the key; null when it could be anything. */
    fun fromKey(raw: String): BrainProvider? {
        val key = raw.trim()
        return when {
            key.startsWith("sk-or-") -> OpenRouter
            key.startsWith("sk-ant-") -> Anthropic
            key.startsWith("gsk_") -> Groq
            key.startsWith("AIza") -> Gemini
            deepSeekKey.matches(key) -> DeepSeek
            // Plain "sk-" is used by several services; only OpenAI's long keys are a safe guess.
            key.startsWith("sk-proj-") || key.startsWith("sk-svcacct-") || (key.startsWith("sk-") && key.length >= 45) -> OpenAI
            else -> null
        }
    }

    private val nonChatModel =
        Regex("embed|whisper|tts|dall-e|moderation|image|audio|realtime|transcribe|search|guard|imagen|veo|aqa", RegexOption.IGNORE_CASE)

    /** Only chat models are useful here: drop embeddings, speech, images and the like. */
    fun chatModels(ids: List<String>): List<String> = ids.filterNot { nonChatModel.containsMatchIn(it) }.map { it.removePrefix("models/") }

    /** A sensible default among the models the server offers. */
    fun pickDefault(
        provider: BrainProvider,
        models: List<String>,
    ): String? {
        for (want in provider.preferredModels) {
            val hit =
                models.firstOrNull { it.equals(want, ignoreCase = true) }
                    ?: models.firstOrNull { it.contains(want, ignoreCase = true) }
            if (hit != null) return hit
        }
        return models.firstOrNull() ?: provider.preferredModels.firstOrNull()
    }
}
