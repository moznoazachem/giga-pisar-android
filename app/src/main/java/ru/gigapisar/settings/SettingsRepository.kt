package ru.gigapisar.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.gigapisar.brain.Brain

private val Context.settingsDataStore by preferencesDataStore(
    name = "giga_pisar_settings",
)

enum class InsertionMode {
    CLIPBOARD,
    TEXT_FIELD,
}

object SettingsRepository {
    private val insertionModeKey =
        stringPreferencesKey("insertion_mode")

    private val virtualButtonVisibleKey =
        booleanPreferencesKey("virtual_button_visible")

    private val volumeKeyEnabledKey =
        booleanPreferencesKey("volume_key_enabled")

    private val sendKeyKey =
        booleanPreferencesKey("send_key")

    private val fabScaleKey =
        floatPreferencesKey("fab_scale")

    private val fabHiddenAppsKey =
        stringSetPreferencesKey("fab_hidden_apps")

    private val noClipboardKey =
        booleanPreferencesKey("no_clipboard")

    private val vibrationEnabledKey =
        booleanPreferencesKey("vibration_enabled")

    private val simpleSyntaxKey =
        booleanPreferencesKey("simple_syntax")

    private val fabGesturesKey =
        booleanPreferencesKey("fab_gestures")

    private val dropHesitationsKey =
        booleanPreferencesKey("drop_hesitations")

    private val noFinalPeriodKey =
        booleanPreferencesKey("no_final_period")

    private const val BRAIN_MODEL_PREFIX = "brain_model_for_"

    /** A page or two of rules is plenty; more only slows the model down. */
    const val BRAIN_PROMPT_MAX = 8000

    private val brainEnabledKey = booleanPreferencesKey("brain_enabled")
    private val brainEveryTakeKey = booleanPreferencesKey("brain_every_take")
    private val brainShortenKey = booleanPreferencesKey("brain_shorten")
    private val brainSkipShortKey = booleanPreferencesKey("brain_skip_short")

    private fun brainPromptKey(kind: Brain.Kind) = stringPreferencesKey("brain_prompt_" + kind.name.lowercase())
    private val brainProviderKey = stringPreferencesKey("brain_provider")
    private val brainModelKey = stringPreferencesKey("brain_model")

    /** The address of a local Ollama server in the home network, like "http://192.168.1.10:11434". */
    private val ollamaUrlKey = stringPreferencesKey("ollama_url")

    /** The address of a service of one's own, like "https://api.example.com/v1". */
    private val customUrlKey = stringPreferencesKey("custom_url")

    fun ollamaUrl(context: Context): Flow<String> =
        context.settingsDataStore.data.map { preferences ->
            preferences[ollamaUrlKey] ?: ""
        }

    fun customUrl(context: Context): Flow<String> =
        context.settingsDataStore.data.map { preferences ->
            preferences[customUrlKey] ?: ""
        }

    private fun ollamaKey() = ollamaUrlKey

    suspend fun setOllamaUrl(
        context: Context,
        url: String,
    ) {
        context.settingsDataStore.edit { it[ollamaUrlKey] = url.trim().trimEnd('/') }
    }

    suspend fun setCustomUrl(
        context: Context,
        url: String,
    ) {
        context.settingsDataStore.edit { it[customUrlKey] = url.trim().trimEnd('/') }
    }

    fun insertionMode(context: Context): Flow<InsertionMode> =
        context.settingsDataStore.data.map { preferences ->
            when (
                preferences[insertionModeKey]
            ) {
                InsertionMode.CLIPBOARD.name -> InsertionMode.CLIPBOARD
                else -> InsertionMode.TEXT_FIELD
            }
        }

    fun virtualButtonVisible(context: Context): Flow<Boolean> =
        context.settingsDataStore.data.map { preferences ->
            preferences[virtualButtonVisibleKey] ?: true
        }

    /** Floating button size relative to the original, [FAB_SCALE_MIN]..[FAB_SCALE_MAX]. */
    fun fabScale(context: Context): Flow<Float> =
        context.settingsDataStore.data.map { preferences ->
            (preferences[fabScaleKey] ?: 1f).coerceIn(FAB_SCALE_MIN, FAB_SCALE_MAX)
        }

    suspend fun setFabScale(
        context: Context,
        scale: Float,
    ) {
        context.settingsDataStore.edit { it[fabScaleKey] = scale.coerceIn(FAB_SCALE_MIN, FAB_SCALE_MAX) }
    }

    /** Apps (package names) where the floating button stays hidden; the volume key still works there. */
    fun fabHiddenApps(context: Context): Flow<Set<String>> =
        context.settingsDataStore.data.map { preferences ->
            preferences[fabHiddenAppsKey] ?: emptySet()
        }

    suspend fun setFabHiddenApps(
        context: Context,
        packages: Set<String>,
    ) {
        context.settingsDataStore.edit { it[fabHiddenAppsKey] = packages }
    }

    const val FAB_SCALE_MIN = 0.6f
    const val FAB_SCALE_MAX = 1.4f

    suspend fun setInsertionMode(
        context: Context,
        mode: InsertionMode,
    ) {
        context.settingsDataStore.edit { preferences ->
            preferences[insertionModeKey] = mode.name
        }
    }

    suspend fun setVirtualButtonVisible(
        context: Context,
        visible: Boolean,
    ) {
        context.settingsDataStore.edit { preferences ->
            preferences[virtualButtonVisibleKey] = visible
        }
    }

    fun volumeKeyEnabled(context: Context): Flow<Boolean> =
        context.settingsDataStore.data.map { preferences ->
            preferences[volumeKeyEnabledKey] ?: true
        }

    /** Volume up right after a dictation taps the messenger's "Send" button. */
    fun sendKey(context: Context): Flow<Boolean> =
        context.settingsDataStore.data.map { preferences ->
            preferences[sendKeyKey] ?: false
        }

    suspend fun setSendKey(
        context: Context,
        enabled: Boolean,
    ) {
        context.settingsDataStore.edit { it[sendKeyKey] = enabled }
    }

    /** Never paste through the clipboard: where a field does not take text directly, say so instead. */
    fun noClipboard(context: Context): Flow<Boolean> =
        context.settingsDataStore.data.map { preferences ->
            preferences[noClipboardKey] ?: false
        }

    suspend fun setNoClipboard(
        context: Context,
        enabled: Boolean,
    ) {
        context.settingsDataStore.edit { it[noClipboardKey] = enabled }
    }

    /** A single dictated sentence goes in lowercase and without the final period, like a chat line. */
    fun simpleSyntax(context: Context): Flow<Boolean> =
        context.settingsDataStore.data.map { preferences ->
            preferences[simpleSyntaxKey] ?: false
        }

    suspend fun setSimpleSyntax(
        context: Context,
        enabled: Boolean,
    ) {
        context.settingsDataStore.edit { it[simpleSyntaxKey] = enabled }
    }

    /** "Э-э", "а-а", "м-м" are cut out of every take, on the phone itself. */
    fun dropHesitations(context: Context): Flow<Boolean> =
        context.settingsDataStore.data.map { preferences ->
            preferences[dropHesitationsKey] ?: true
        }

    suspend fun setDropHesitations(
        context: Context,
        enabled: Boolean,
    ) {
        context.settingsDataStore.edit { it[dropHesitationsKey] = enabled }
    }

    /** The take goes in without its final period, however many sentences it has. */
    fun noFinalPeriod(context: Context): Flow<Boolean> =
        context.settingsDataStore.data.map { preferences ->
            preferences[noFinalPeriodKey] ?: false
        }

    suspend fun setNoFinalPeriod(
        context: Context,
        enabled: Boolean,
    ) {
        context.settingsDataStore.edit { it[noFinalPeriodKey] = enabled }
    }

    fun vibrationEnabled(context: Context): Flow<Boolean> =
        context.settingsDataStore.data.map { preferences ->
            preferences[vibrationEnabledKey] ?: true
        }

    suspend fun setVolumeKeyEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        context.settingsDataStore.edit { preferences ->
            preferences[volumeKeyEnabledKey] = enabled
        }
    }

    /** Slide up for hands-free and toward the middle to cancel, on the floating button. */
    fun fabGestures(context: Context): Flow<Boolean> =
        context.settingsDataStore.data.map { preferences ->
            preferences[fabGesturesKey] ?: true
        }

    suspend fun setFabGestures(
        context: Context,
        enabled: Boolean,
    ) {
        context.settingsDataStore.edit { preferences ->
            preferences[fabGesturesKey] = enabled
        }
    }

    suspend fun setVibrationEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        context.settingsDataStore.edit { preferences ->
            preferences[vibrationEnabledKey] = enabled
        }
    }

    /** Brain settings in one piece; the key itself lives in [ru.gigapisar.brain.KeyVault]. */
    data class BrainSettings(
        val enabled: Boolean = false,
        val everyTake: Boolean = false,
        /** "Edit on the fly" style: false tidies the take up, true makes a short message of it. */
        val shorten: Boolean = false,
        /** "Edit on the fly" leaves a short take alone: it goes in at once, with no request. */
        val skipShort: Boolean = true,
        /** The parts of the instructions a person rewrote; the core rules are not stored. */
        val prompts: Brain.Custom = Brain.Custom(),
        val providerId: String? = null,
        val model: String? = null,
        /** The address of the local Ollama server in the home network. */
        val ollamaUrl: String = "",
        /** The address of a service of one's own. */
        val customUrl: String = "",
        /** The last model chosen for each service, so switching services keeps each one's pick. */
        val modelsByProvider: Map<String, String> = emptyMap(),
    )

    fun brain(context: Context): Flow<BrainSettings> =
        context.settingsDataStore.data.map { preferences ->
            BrainSettings(
                enabled = preferences[brainEnabledKey] ?: false,
                everyTake = preferences[brainEveryTakeKey] ?: false,
                shorten = preferences[brainShortenKey] ?: false,
                skipShort = preferences[brainSkipShortKey] ?: true,
                prompts =
                    Brain.Custom(
                        command = preferences[brainPromptKey(Brain.Kind.COMMAND)],
                        tidy = preferences[brainPromptKey(Brain.Kind.TIDY)],
                        shorten = preferences[brainPromptKey(Brain.Kind.SHORTEN)],
                    ),
                providerId = preferences[brainProviderKey],
                model = preferences[brainModelKey],
                ollamaUrl = preferences[ollamaKey()] ?: "",
                customUrl = preferences[customUrlKey] ?: "",
                modelsByProvider =
                    preferences
                        .asMap()
                        .filterKeys { it.name.startsWith(BRAIN_MODEL_PREFIX) }
                        .map { (k, v) -> k.name.removePrefix(BRAIN_MODEL_PREFIX) to v.toString() }
                        .toMap(),
            )
        }

    suspend fun setBrainEnabled(
        context: Context,
        enabled: Boolean,
    ) {
        context.settingsDataStore.edit { it[brainEnabledKey] = enabled }
    }

    suspend fun setBrainEveryTake(
        context: Context,
        enabled: Boolean,
    ) {
        context.settingsDataStore.edit { it[brainEveryTakeKey] = enabled }
    }

    suspend fun setBrainShorten(
        context: Context,
        shorten: Boolean,
    ) {
        context.settingsDataStore.edit { it[brainShortenKey] = shorten }
    }

    suspend fun setBrainSkipShort(
        context: Context,
        enabled: Boolean,
    ) {
        context.settingsDataStore.edit { it[brainSkipShortKey] = enabled }
    }

    /** A rewritten body of the instructions; null or the standard text puts the standard one back. */
    suspend fun setBrainPrompt(
        context: Context,
        kind: Brain.Kind,
        body: String?,
    ) {
        val own = body?.trim()?.take(BRAIN_PROMPT_MAX)?.takeIf { it.isNotEmpty() && it != Brain.instructions(kind).body.trim() }
        context.settingsDataStore.edit { if (own == null) it.remove(brainPromptKey(kind)) else it[brainPromptKey(kind)] = own }
    }

    /** Forgets a service's saved model along with its key. */
    suspend fun forgetBrainService(
        context: Context,
        providerId: String,
    ) {
        context.settingsDataStore.edit { it.remove(stringPreferencesKey(BRAIN_MODEL_PREFIX + providerId)) }
    }

    /** A checked service: remembers where to send text and which model answers. */
    suspend fun setBrainService(
        context: Context,
        providerId: String?,
        model: String?,
    ) {
        context.settingsDataStore.edit { preferences ->
            if (providerId == null) preferences.remove(brainProviderKey) else preferences[brainProviderKey] = providerId
            if (model == null) preferences.remove(brainModelKey) else preferences[brainModelKey] = model
            if (providerId != null && model != null) preferences[stringPreferencesKey(BRAIN_MODEL_PREFIX + providerId)] = model
        }
    }
}
