package ru.gigapisar.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrainTest {
    @Test
    fun commandAfterAddress() {
        assertEquals(
            "Жду ответа до пятницы." to "сделай короче",
            Brain.parseCommand("Жду ответа до пятницы. Писарь, сделай короче."),
        )
        assertEquals(
            "Созвон в пять" to "переведи на английский",
            Brain.parseCommand("Созвон в пять, Гига Писарь, переведи на английский"),
        )
        // Recognition often hears "песарь".
        assertEquals("Текст." to "исправь", Brain.parseCommand("Текст. Песарь, исправь"))
    }

    @Test
    fun cancelPhraseDropsTheTake() {
        assertEquals("Привет, как дела?", Brain.cancelledBody("Привет, как дела? Писарь, отмена."))
        assertEquals("Привет.", Brain.cancelledBody("Привет. Гига Писарь, отмена"))
        assertEquals("", Brain.cancelledBody("Писарь, отмена."))
        assertEquals("Встреча отменена", Brain.cancelledBody("Встреча отменена, песарь, отбой"))
        // A command that only starts with "отмени" is a command, not a cancel.
        assertNull(Brain.cancelledBody("Писарь, отмени последнее предложение."))
        assertNull(Brain.cancelledBody("Отмена встречи в пять."))
        assertNull(Brain.cancelledBody("Купить хлеб. Писарь, исправь"))
    }

    @Test
    fun lastAddressWins() {
        assertEquals(
            "Я пользуюсь Писарем, и писарь хороший." to "сократи",
            Brain.parseCommand("Я пользуюсь Писарем, и писарь хороший. Писарь, сократи"),
        )
    }

    @Test
    fun noCommandWithoutAddressOrText() {
        assertNull(Brain.parseCommand("Просто текст без обращения"))
        assertNull(Brain.parseCommand("Писарь, сделай короче"))
        assertNull(Brain.parseCommand("Какой-то текст. Писарь"))
        // "писарей" is not an address.
        assertNull(Brain.parseCommand("Было много писарей в канцелярии"))
    }

    @Test
    fun serviceFromKey() {
        assertEquals(BrainProviders.DeepSeek, BrainProviders.fromKey("sk-" + "0123456789abcdef".repeat(2)))
        assertEquals(BrainProviders.OpenRouter, BrainProviders.fromKey("sk-or-v1-abc"))
        assertEquals(BrainProviders.Anthropic, BrainProviders.fromKey("sk-ant-api03-abc"))
        assertEquals(BrainProviders.Groq, BrainProviders.fromKey("gsk_abc"))
        assertEquals(BrainProviders.Gemini, BrainProviders.fromKey("AIzaSyabc"))
        assertEquals(BrainProviders.OpenAI, BrainProviders.fromKey("sk-proj-abc"))
        assertNull(BrainProviders.fromKey("sk-short"))
    }

    @Test
    fun ollamaBaseUrlUsesOpenAiCompatibleApiPath() {
        assertEquals("http://192.168.1.10:11434/v1", BrainProviders.ollamaAt("http://192.168.1.10:11434/").baseUrl)
        assertEquals("http://192.168.1.10:11434/v1", BrainProviders.ollamaAt("http://192.168.1.10:11434/v1/").baseUrl)
    }

    @Test
    fun plainHttpOnlyAtHome() {
        assertEquals(true, BrainClient.onHomeNetwork("192.168.1.10"))
        assertEquals(true, BrainClient.onHomeNetwork("10.0.0.5"))
        assertEquals(true, BrainClient.onHomeNetwork("100.101.102.103"))
        assertEquals(true, BrainClient.onHomeNetwork("127.0.0.1"))
        assertEquals(false, BrainClient.onHomeNetwork("8.8.8.8"))
    }

    @Test
    fun failureInPlainWords() {
        java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ru"))
        assertEquals("сервис не принял ключ", BrainClient.describeFailure(401, """{"error":{"message":"Invalid key"}}"""))
        assertEquals(
            "на счету сервиса нет денег или не подключена оплата API (это отдельно от подписки вроде ChatGPT Plus)",
            BrainClient.describeFailure(402, """{"error":{"message":"Insufficient Balance"}}"""),
        )
        java.util.Locale.setDefault(java.util.Locale.ENGLISH)
        assertEquals("the service rejected the key", BrainClient.describeFailure(401, "{}"))
    }
}
