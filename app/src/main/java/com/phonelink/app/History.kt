package com.phonelink.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Saved on the phone itself, so it can be read with no connection at all. */
object History {
    class Rec(
        val kind: String,   // down | up | play | recv | sent | pair | conn
        val name: String,
        val size: Long,
        val ok: Boolean,
        val at: Long,
        val speed: Double,  // MB/s
        val peer: String,
        val path: String
    )

    private val lock = Any()

    private fun file(ctx: Context) = File(ctx.filesDir, "history.json")

    private fun readArr(ctx: Context): JSONArray = try {
        val f = file(ctx)
        if (f.exists()) JSONArray(f.readText()) else JSONArray()
    } catch (e: Exception) {
        JSONArray()
    }

    fun add(
        ctx: Context,
        kind: String,
        name: String,
        size: Long = 0L,
        ok: Boolean = true,
        speed: Double = 0.0,
        peer: String = "",
        path: String = ""
    ) {
        try {
            synchronized(lock) {
                val old = readArr(ctx)
                val out = JSONArray()
                out.put(
                    JSONObject().put("k", kind).put("n", name).put("s", size).put("ok", ok)
                        .put("t", System.currentTimeMillis()).put("v", speed).put("p", peer).put("f", path)
                )
                for (i in 0 until minOf(old.length(), 499)) out.put(old.get(i))
                file(ctx).writeText(out.toString())
            }
        } catch (e: Exception) {
        }
    }

    fun all(ctx: Context): List<Rec> {
        val a = synchronized(lock) { readArr(ctx) }
        val out = ArrayList<Rec>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            out.add(
                Rec(
                    o.optString("k"), o.optString("n"), o.optLong("s"), o.optBoolean("ok", true),
                    o.optLong("t"), o.optDouble("v", 0.0), o.optString("p"), o.optString("f")
                )
            )
        }
        return out
    }

    fun remove(ctx: Context, index: Int) {
        try {
            synchronized(lock) {
                val old = readArr(ctx)
                val out = JSONArray()
                for (i in 0 until old.length()) if (i != index) out.put(old.get(i))
                file(ctx).writeText(out.toString())
            }
        } catch (e: Exception) {
        }
    }

    fun clear(ctx: Context) {
        try {
            synchronized(lock) { file(ctx).delete() }
        } catch (e: Exception) {
        }
    }
}
