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

    private val fabScaleKey =
        floatPreferencesKey("fab_scale")

    private val fabHiddenAppsKey =
        stringSetPreferencesKey("fab_hidden_apps")

    private val noClipboardKey =
        booleanPreferencesKey("no_clipboard")

    private val vibrationEnabledKey =
        booleanPreferencesKey("vibration_enabled")

    private const val BRAIN_MODEL_PREFIX = "brain_model_for_"

    private val brainEnabledKey = booleanPreferencesKey("brain_enabled")
    private val brainEveryTakeKey = booleanPreferencesKey("brain_every_take")
    private val brainProviderKey = stringPreferencesKey("brain_provider")
    private val brainModelKey = stringPreferencesKey("brain_model")

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
        val providerId: String? = null,
        val model: String? = null,
        /** The last model chosen for each service, so switching services keeps each one's pick. */
        val modelsByProvider: Map<String, String> = emptyMap(),
    )

    fun brain(context: Context): Flow<BrainSettings> =
        context.settingsDataStore.data.map { preferences ->
            BrainSettings(
                enabled = preferences[brainEnabledKey] ?: false,
                everyTake = preferences[brainEveryTakeKey] ?: false,
                providerId = preferences[brainProviderKey],
                model = preferences[brainModelKey],
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
