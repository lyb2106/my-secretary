package com.lyb.mysecretary.data

import android.content.Context

enum class ExtractionMode(val label: String) {
    AUTO("Gemini Nano 우선 (실패 시 규칙 기반)"),
    RULE_ONLY("규칙 기반만 사용"),
}

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var autoCopy: Boolean
        get() = prefs.getBoolean("auto_copy", true)
        set(value) = prefs.edit().putBoolean("auto_copy", value).apply()

    var extractionMode: ExtractionMode
        get() = runCatching { ExtractionMode.valueOf(prefs.getString("extraction_mode", null) ?: "") }
            .getOrDefault(ExtractionMode.AUTO)
        set(value) = prefs.edit().putString("extraction_mode", value.name).apply()
}
