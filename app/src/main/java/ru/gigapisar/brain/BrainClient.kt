package ru.gigapisar.brain

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Locale

/** Russian on a Russian phone, English otherwise, like the rest of the interface. */
internal fun say(
    ru: String,
    en: String,
): String = if (brainRussian ?: (Locale.getDefault().language == "ru")) ru else en

/** Set from the app's language setting; null means follow the phone. */
@Volatile
var brainRussian: Boolean? = null

/** A failure the person can act on, already in plain words. */
class BrainException(
    message: String,
) : Exception(message)

/**
 * OpenAI-compatible chat API, the same requests the desktop apps send. Blocking: call it
 * off the main thread.
 */
object BrainClient {
    /**
     * The address to send requests to: for local servers the one the person typed in the
     * settings, for cloud services the one from the table.
     */
    @Volatile
    var localBaseUrl: String? = null

    fun baseUrl(provider: BrainProvider): String =
        if (provider.local) (localBaseUrl ?: provider.baseUrl).trimEnd('/') else provider.baseUrl
    /**
     * reasoning_effort is not part of every OpenAI-compatible API; some servers reject it
     * with 400. We send it until a server refuses once, then stop for the rest of the run.
     */
    @Volatile
    private var sendReasoningEffort = true

    fun complete(
        provider: BrainProvider,
        key: String,
        model: String,
        prompt: String,
        text: String,
        timeoutMs: Int,
    ): String {
        val withReasoning = sendReasoningEffort
        val (status, body) = post(provider, key, model, prompt, text, withReasoning, timeoutMs)
        if (withReasoning && (status == 400 || status == 422) && body.contains("reasoning", ignoreCase = true)) {
            // Only drop the parameter when the server complains about it; a wrong model name is a real error.
            sendReasoningEffort = false
            val (retryStatus, retryBody) = post(provider, key, model, prompt, text, false, timeoutMs)
            return readContent(retryStatus, retryBody)
        }
        return readContent(status, body)
    }

    /** A tiny real request: a key that lists models may still be unable to chat (no balance, no access). */
    fun probe(
        provider: BrainProvider,
        key: String,
        model: String,
    ) {
        complete(provider, key, model, "Ответь одним словом: ок", "ок", 20_000)
    }

    fun listModels(
        provider: BrainProvider,
        key: String,
    ): List<String> {
        val (status, body) = request("GET", "${baseUrl(provider)}/models", key, null, 20_000)
        if (status !in 200..299) throw BrainException(describeFailure(status, body))
        val data = JSONObject(body).getJSONArray("data")
        return (0 until data.length()).mapNotNull { data.getJSONObject(it).optString("id").takeIf { id -> id.isNotBlank() } }
    }

    private fun post(
        provider: BrainProvider,
        key: String,
        model: String,
        prompt: String,
        text: String,
        withReasoning: Boolean,
        timeoutMs: Int,
    ): Pair<Int, String> {
        val payload =
            JSONObject()
                .put("model", model)
                .put(
                    "messages",
                    JSONArray()
                        .put(JSONObject().put("role", "system").put("content", prompt))
                        .put(JSONObject().put("role", "user").put("content", text)),
                )
        if (withReasoning) payload.put("reasoning_effort", "none")
        return request("POST", "${baseUrl(provider)}/chat/completions", key, payload.toString(), timeoutMs)
    }

    private fun request(
        method: String,
        url: String,
        key: String,
        body: String?,
        timeoutMs: Int,
    ): Pair<Int, String> {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = minOf(timeoutMs, 10_000)
            connection.readTimeout = timeoutMs
            // A local server takes no key; sending an empty Authorization confuses nothing.
            if (key.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer ${key.trim()}")
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                // Fixed length, not chunked: some small servers do not accept chunked bodies.
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return status to text
        } catch (_: SocketTimeoutException) {
            throw BrainException(
                say("сервис не ответил за ${timeoutMs / 1000} секунд", "the service did not answer within ${timeoutMs / 1000} seconds"),
            )
        } catch (_: IOException) {
            throw BrainException(
                say(
                    "нет связи с сервером, проверьте адрес и что телефон в той же сети",
                    "cannot reach the server, check the address and that the phone is on the same network",
                ),
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun readContent(
        status: Int,
        body: String,
    ): String {
        if (status !in 200..299) throw BrainException(describeFailure(status, body))
        var answer =
            try {
                JSONObject(body)
                    .getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
            } catch (_: Exception) {
                throw BrainException(say("сервис ответил непонятно", "the service gave an unreadable answer"))
            }
        // A model that still thought aloud: keep only the answer.
        val think = answer.indexOf("</think>")
        if (think >= 0) answer = answer.substring(think + "</think>".length)
        return answer.trim()
    }

    /** The error text an OpenAI-style server puts in {"error":{"message":…}} (or {"message":…}). */
    private fun serverMessage(body: String): String =
        try {
            val trimmed = body.trim()
            val root = if (trimmed.startsWith("[")) JSONArray(trimmed).getJSONObject(0) else JSONObject(trimmed)
            val error = root.opt("error")
            when {
                error is String -> error
                error is JSONObject -> error.optString("message")
                else -> root.optString("message")
            }
        } catch (_: Exception) {
            body.take(200)
        }

    /** What went wrong, in words a person can act on. Same wording as the desktop apps. */
    fun describeFailure(
        status: Int,
        body: String,
    ): String {
        val m = (serverMessage(body) + " " + body).lowercase()
        return when {
            status == 402 || listOf("insufficient_quota", "quota", "billing", "balance", "credit", "payment").any { it in m } ->
                say(
                    "на счету сервиса нет денег или не подключена оплата API (это отдельно от подписки вроде ChatGPT Plus)",
                    "no money on the service account or API billing is not set up (separate from subscriptions like ChatGPT Plus)",
                )
            listOf("country", "region", "territory", "location").any {
                it in m
            } -> say("сервис недоступен из вашей страны", "the service is not available in your country")
            status == 401 -> say("сервис не принял ключ", "the service rejected the key")
            status == 403 -> say("у ключа нет доступа к этой модели или сервису", "the key has no access to this model or service")
            status == 404 || ("model" in m && ("not found" in m || "not exist" in m)) ->
                if (localBaseUrl != null) {
                    say(
                        "модель не установлена на сервере, выполните на нём: ollama pull <модель>",
                        "the model is not installed on the server, run there: ollama pull <model>",
                    )
                } else {
                    say("модель недоступна для этого ключа, выберите другую", "the model is not available for this key, pick another one")
                }
            status == 429 -> say("слишком много запросов, попробуйте через минуту", "too many requests, try again in a minute")
            status >= 500 -> say("у сервиса сбой (ошибка $status), попробуйте позже", "the service is failing (error $status), try later")
            else -> say("сервис ответил ошибкой $status", "the service answered with error $status")
        }
    }
}
