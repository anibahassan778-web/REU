package com.reu.core.logging

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class LogLevel {
  INFO,
  WARN,
  ERROR,
  DEBUG,
}

data class LogEntry(
  val timestampMs: Long,
  val level: LogLevel,
  val tag: String,
  val message: String,
  val projectId: String? = null,
) {
  fun formatted(): String {
    val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    return "[${fmt.format(Date(timestampMs))}] [$level] [$tag] $message"
  }
}

class ReuLogger {
  private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
  val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

  private var logSinkDir: File? = null

  fun attachLogDirectory(dir: File) {
    if (!dir.exists()) dir.mkdirs()
    logSinkDir = dir
  }

  fun info(tag: String, message: String, projectId: String? = null) =
    append(LogLevel.INFO, tag, message, projectId)

  fun warn(tag: String, message: String, projectId: String? = null) =
    append(LogLevel.WARN, tag, message, projectId)

  fun error(tag: String, message: String, projectId: String? = null) =
    append(LogLevel.ERROR, tag, message, projectId)

  fun debug(tag: String, message: String, projectId: String? = null) =
    append(LogLevel.DEBUG, tag, message, projectId)

  private fun append(level: LogLevel, tag: String, message: String, projectId: String?) {
    val entry = LogEntry(System.currentTimeMillis(), level, tag, message, projectId)
    _logs.update { current ->
      val updated = current + entry
      if (updated.size > MAX_IN_MEMORY_LOGS) updated.takeLast(MAX_IN_MEMORY_LOGS) else updated
    }
    logSinkDir?.let { base ->
      runCatching {
        val file =
          if (projectId != null) File(base, "project_${projectId}_log.txt")
          else File(base, "reu_session.log")
        file.appendText(entry.formatted() + "\n")
      }
    }
  }

  fun clear() {
    _logs.value = emptyList()
  }

  companion object {
    private const val MAX_IN_MEMORY_LOGS = 500
  }
}
