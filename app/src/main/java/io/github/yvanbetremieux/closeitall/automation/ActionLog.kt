package io.github.yvanbetremieux.closeitall.automation

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Last [MAX] automation actions, persisted in a small text file. */
class ActionLog(context: Context) {
    private val file = File(context.filesDir, "actions.log")
    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<String>> = _entries.asStateFlow()

    @Synchronized
    fun add(message: String) {
        val stamp = SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE).format(Date())
        Log.i("CloseItAll", message)
        val updated = (listOf("$stamp  $message") + _entries.value).take(MAX)
        _entries.value = updated
        runCatching { file.writeText(updated.joinToString("\n")) }
    }

    private fun load(): List<String> = runCatching {
        if (file.exists()) file.readLines().filter { it.isNotBlank() }.take(MAX) else emptyList()
    }.getOrDefault(emptyList())

    private companion object {
        const val MAX = 50
    }
}
