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

    fun addManual(wrong: String, right: String) = synchronized(lock) {
        val w = wrong.trim()
        val r = right.trim()
        if (w.isEmpty() || r.isEmpty() || w == r) return@synchronized
        write(read().filterNot { it.wrong == w && it.right == r } + Correction(w, r, Correction.PROMOTE_AT, manual = true))
    }

    fun remove(rule: Correction) = synchronized(lock) {
        write(read().filterNot { it.wrong == rule.wrong && it.right == rule.right })
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
