package com.lyb.mysecretary.data

import android.content.Context
import com.lyb.mysecretary.learn.Correction
import com.lyb.mysecretary.learn.Corrections
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Persists learned and manual corrections (whitelist) as JSON. */
class WhitelistStore(context: Context) {
    private val file = File(context.filesDir, "whitelist.json")
    private val lock = Any()

    fun all(): List<Correction> = synchronized(lock) { read() }

    /** Learns from a user edit of generated text. Returns the number of candidate pairs. */
    fun learn(original: String, edited: String): Int = synchronized(lock) {
        val mined = Corrections.mine(original, edited)
        if (mined.isNotEmpty()) write(Corrections.merge(read(), mined))
        mined.size
    }

    /** Adds a whole-word rule. Returns false when either side is not a single word. */
    fun addManual(wrong: String, right: String): Boolean = synchronized(lock) {
        val w = wrong.trim()
        val r = right.trim()
        if (!Corrections.isWord(w) || !Corrections.isWord(r) || w == r) return@synchronized false
        write(read().filterNot { it.wrong == w } + Correction(w, r, Correction.PROMOTE_AT, manual = true))
        true
    }

    /**
     * v0.1.8: rules must be whole words. Drops syllable/phrase rules learned by earlier versions
     * (e.g. "영 → 형", "현 교수님 내연권 → …") and adds the word rules the user asked for instead.
     * Runs once.
     */
    fun migrateToWordRules(prefs: android.content.SharedPreferences) = synchronized(lock) {
        if (prefs.getBoolean(MIGRATED_KEY, false)) return@synchronized
        val kept = read().filter { Corrections.isValidRule(it) }
        val requested = listOf("김보영" to "김보형", "매일" to "메일", "내연권" to "뇌연구원")
            .map { (w, r) -> Correction(w, r, Correction.PROMOTE_AT, manual = true) }
        write(kept.filterNot { k -> requested.any { it.wrong == k.wrong } } + requested)
        prefs.edit().putBoolean(MIGRATED_KEY, true).apply()
    }

    fun remove(rule: Correction) = synchronized(lock) {
        write(read().filterNot { it.wrong == rule.wrong && it.right == rule.right })
    }

    private companion object {
        const val MIGRATED_KEY = "whitelist_word_rules_v1"
    }

    private fun read(): List<Correction> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Correction(o.getString("wrong"), o.getString("right"), o.optInt("count", 1), o.optBoolean("manual", false))
            }
        }.getOrDefault(emptyList())
    }

    private fun write(rules: List<Correction>) {
        val arr = JSONArray()
        rules.forEach {
            arr.put(JSONObject().put("wrong", it.wrong).put("right", it.right).put("count", it.count).put("manual", it.manual))
        }
        val tmp = File(file.parentFile, "whitelist.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }
}
