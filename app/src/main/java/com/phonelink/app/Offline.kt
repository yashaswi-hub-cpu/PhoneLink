package com.phonelink.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Saved copies of folder lists, so the other phone's storage can be browsed with no connection at all. */
object Offline {
    private fun root(ctx: Context) = File(ctx.filesDir, "offline")

    private fun dir(ctx: Context, key: String) =
        File(root(ctx), key.replace(Regex("[^A-Za-z0-9._-]"), "_"))

    private fun file(ctx: Context, key: String, path: String): File =
        File(dir(ctx, key), Crypto.hex(MessageDigest.getInstance("SHA-1").digest(path.toByteArray())) + ".json")

    fun save(ctx: Context, key: String, path: String, listing: String) {
        try {
            dir(ctx, key).mkdirs()
            file(ctx, key, path).writeText(
                JSONObject().put("t", System.currentTimeMillis()).put("a", JSONArray(listing)).toString()
            )
        } catch (e: Exception) {
        }
    }

    /** (time saved, folder list as JSON text), or null if this folder was never saved. */
    fun load(ctx: Context, key: String, path: String): Pair<Long, String>? = try {
        val f = file(ctx, key, path)
        if (!f.exists()) {
            null
        } else {
            val o = JSONObject(f.readText())
            Pair(o.getLong("t"), o.getJSONArray("a").toString())
        }
    } catch (e: Exception) {
        null
    }

    fun clear(ctx: Context) {
        try {
            root(ctx).deleteRecursively()
        } catch (e: Exception) {
        }
    }
}
