package com.reu.core.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.reu.core.common.AppLanguage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.reuSettingsDataStore: DataStore<Preferences> by
  preferencesDataStore(name = "reu_settings")

data class UserSettings(
  val language: AppLanguage = AppLanguage.SYSTEM,
  val offlineFirstMode: Boolean = true,
  val streamingBufferKb: Int = 256,
  val activeProjectId: String? = null,
)

class SettingsDataStore(private val context: Context) {
  private object Keys {
    val LANGUAGE = stringPreferencesKey("app_language")
    val OFFLINE_FIRST = booleanPreferencesKey("offline_first_mode")
    val BUFFER_KB = intPreferencesKey("streaming_buffer_kb")
    val ACTIVE_PROJECT_ID = stringPreferencesKey("active_project_id")
  }

  val settingsFlow: Flow<UserSettings> =
    context.reuSettingsDataStore.data.map { prefs ->
      val lang =
        runCatching {
          AppLanguage.valueOf(prefs[Keys.LANGUAGE] ?: AppLanguage.SYSTEM.name)
        }.getOrDefault(AppLanguage.SYSTEM)
      UserSettings(
        language = lang,
        offlineFirstMode = prefs[Keys.OFFLINE_FIRST] ?: true,
        streamingBufferKb = (prefs[Keys.BUFFER_KB] ?: 256).coerceIn(64, 1024),
        activeProjectId = prefs[Keys.ACTIVE_PROJECT_ID],
      )
    }

  suspend fun setLanguage(language: AppLanguage) {
    context.reuSettingsDataStore.edit { it[Keys.LANGUAGE] = language.name }
  }

  suspend fun setOfflineFirstMode(enabled: Boolean) {
    context.reuSettingsDataStore.edit { it[Keys.OFFLINE_FIRST] = enabled }
  }

  suspend fun setStreamingBufferKb(kb: Int) {
    context.reuSettingsDataStore.edit { it[Keys.BUFFER_KB] = kb.coerceIn(64, 1024) }
  }

  suspend fun setActiveProjectId(projectId: String?) {
    context.reuSettingsDataStore.edit { prefs ->
      if (projectId == null) prefs.remove(Keys.ACTIVE_PROJECT_ID)
      else prefs[Keys.ACTIVE_PROJECT_ID] = projectId
    }
  }
}
