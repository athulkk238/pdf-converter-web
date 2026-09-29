package com.pdfmaster.app.utils

import android.content.Context
import android.content.SharedPreferences
import com.pdfmaster.app.model.HistoryItem
import org.json.JSONArray
import org.json.JSONObject

class HistoryManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("pdf_master_history_prefs", Context.MODE_PRIVATE)

    fun addHistoryItem(item: HistoryItem) {
        val currentList = getHistoryItems().toMutableList()
        currentList.add(0, item) // prepend
        // limit to 50 recent items
        val trimmedList = if (currentList.size > 50) currentList.take(50) else currentList
        saveHistoryItems(trimmedList)
    }

    fun getHistoryItems(): List<HistoryItem> {
        val jsonString = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        val list = mutableListOf<HistoryItem>()
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    HistoryItem(
                        id = obj.optString("id"),
                        title = obj.optString("title"),
                        operationName = obj.optString("operationName"),
                        timeStamp = obj.optLong("timeStamp"),
                        fileUriString = obj.optString("fileUriString"),
                        fileName = obj.optString("fileName"),
                        fileSizeFormatted = obj.optString("fileSizeFormatted"),
                        pageCount = obj.optInt("pageCount")
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun clearHistory() {
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    private fun saveHistoryItems(items: List<HistoryItem>) {
        val jsonArray = JSONArray()
        for (item in items) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("title", item.title)
                put("operationName", item.operationName)
                put("timeStamp", item.timeStamp)
                put("fileUriString", item.fileUriString)
                put("fileName", item.fileName)
                put("fileSizeFormatted", item.fileSizeFormatted)
                put("pageCount", item.pageCount)
            }
            jsonArray.put(obj)
        }
        prefs.edit().putString(KEY_HISTORY, jsonArray.toString()).apply()
    }

    companion object {
        private const val KEY_HISTORY = "history_items"
    }
}
