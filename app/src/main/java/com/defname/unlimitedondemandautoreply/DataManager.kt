package com.defname.unlimitedondemandautoreply

import android.content.Context
import android.net.TrafficStats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DataManager {
    private const val PREFS_NAME = "data_manager_prefs"
    private const val KEY_BASELINE_BYTES = "baseline_bytes"
    private const val KEY_LAST_SMS_TIMESTAMP = "last_sms_timestamp"
    private const val KEY_DATA_HISTORY = "data_usage_history"

    data class HistoryEntry(
        val timestamp: Long,
        val formattedTime: String,
        val bytesUsed: Long,
        val formattedBytes: String
    )

    /**
     * Gets the current total mobile data usage (rx + tx) in bytes.
     * Returns 0 if the device does not support TrafficStats.
     */
    private fun getTotalMobileBytes(): Long {
        val mobileRx = TrafficStats.getMobileRxBytes()
        val mobileTx = TrafficStats.getMobileTxBytes()

        if (mobileRx == TrafficStats.UNSUPPORTED.toLong() || mobileTx == TrafficStats.UNSUPPORTED.toLong()) {
            return 0L
        }
        return mobileRx + mobileTx
    }

    /**
     * Records that an SMS was sent right now, resetting the baseline data usage.
     * Also saves the completed cycle to the history log.
     */
    fun recordSmsSent(context: Context) {
        val currentBytes = getTotalMobileBytes()
        val currentTime = System.currentTimeMillis()

        // Before resetting, save the data usage of the finishing cycle to history
        val dataUsed = getDataUsageSinceLastSms(context)
        val lastTimestamp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_SMS_TIMESTAMP, 0L)

        if (lastTimestamp != 0L) {
            addHistoryEntry(context, lastTimestamp, dataUsed)
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putLong(KEY_BASELINE_BYTES, currentBytes)
            .putLong(KEY_LAST_SMS_TIMESTAMP, currentTime)
            .apply()
    }

    private fun addHistoryEntry(context: Context, timestamp: Long, bytesUsed: Long) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val historyStr = prefs.getString(KEY_DATA_HISTORY, "") ?: ""

        // Format: timestamp|bytesUsed;timestamp|bytesUsed;...
        val newEntry = "$timestamp|$bytesUsed"
        val updatedHistory = if (historyStr.isEmpty()) newEntry else "$newEntry;$historyStr"

        // keep only the last 50 entries to avoid unbounded growth
        val split = updatedHistory.split(";")
        val trimmedHistory = split.take(50).joinToString(";")

        prefs.edit().putString(KEY_DATA_HISTORY, trimmedHistory).apply()
    }

    fun getHistory(context: Context): List<HistoryEntry> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val historyStr = prefs.getString(KEY_DATA_HISTORY, "") ?: ""

        if (historyStr.isEmpty()) return emptyList()

        return historyStr.split(";").mapNotNull { entry ->
            val parts = entry.split("|")
            if (parts.size == 2) {
                val timestamp = parts[0].toLongOrNull() ?: 0L
                val bytes = parts[1].toLongOrNull() ?: 0L
                val formattedTime = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
                HistoryEntry(timestamp, formattedTime, bytes, formatBytes(bytes))
            } else {
                null
            }
        }
    }

    /**
     * Exports the history data to a JSON string.
     */
    fun exportHistoryToJson(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val historyStr = prefs.getString(KEY_DATA_HISTORY, "") ?: ""

        val jsonArray = org.json.JSONArray()
        if (historyStr.isNotEmpty()) {
            historyStr.split(";").forEach { entry ->
                val parts = entry.split("|")
                if (parts.size == 2) {
                    val jsonObj = org.json.JSONObject()
                    jsonObj.put("timestamp", parts[0].toLongOrNull() ?: 0L)
                    jsonObj.put("bytesUsed", parts[1].toLongOrNull() ?: 0L)
                    jsonArray.put(jsonObj)
                }
            }
        }
        return jsonArray.toString(2)
    }

    /**
     * Imports the history data from a JSON string, replacing current history.
     */
    fun importHistoryFromJson(context: Context, jsonString: String): Boolean {
        return try {
            val jsonArray = org.json.JSONArray(jsonString)
            val historyList = mutableListOf<String>()

            for (i in 0 until jsonArray.length()) {
                val jsonObj = jsonArray.getJSONObject(i)
                val timestamp = jsonObj.optLong("timestamp", 0L)
                val bytesUsed = jsonObj.optLong("bytesUsed", 0L)
                historyList.add("$timestamp|$bytesUsed")
            }

            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val historyStr = historyList.joinToString(";")
            prefs.edit().putString(KEY_DATA_HISTORY, historyStr).apply()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Calculates the data usage since the last recorded SMS was sent.
     */
    fun getDataUsageSinceLastSms(context: Context): Long {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val baseline = prefs.getLong(KEY_BASELINE_BYTES, 0L)

        val currentBytes = getTotalMobileBytes()

        // If baseline is 0, we assume no SMS was sent yet, or we start from 0
        if (baseline == 0L || currentBytes < baseline) {
            return 0L
        }

        return currentBytes - baseline
    }

    /**
     * Gets a formatted string of the timestamp when the last SMS was sent.
     */
    fun getLastSmsTimeFormatted(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val timestamp = prefs.getLong(KEY_LAST_SMS_TIMESTAMP, 0L)

        if (timestamp == 0L) return "Never"

        return SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
    }

    /**
     * Formats bytes into a human-readable string (KB, MB, GB)
     */
    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.getDefault(), "%.2f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.getDefault(), "%.2f MB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.getDefault(), "%.2f GB", gb)
    }
}
