package com.phonelink.app

import android.content.Context
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object Mirror {

    data class Root(
        val host: String,
        val remotePath: String,
        val localDir: File,
        val lastScanAt: Long
    )

    fun add(ctx: Context, host: String, remotePath: String, localDir: File): Long {
        val v = android.content.ContentValues().apply {
            put("host", host)
            put("remote_path", remotePath)
            put("local_dir", localDir.absolutePath)
            put("last_scan_at", 0L)
        }
        return HistoryDb.get(ctx).getWritableDatabase().insert("mirror", null, v)
    }

    fun scan(ctx: Context, host: String, remotePath: String, localDir: File) {
        val token = Pairing.getToken(ctx, host) ?: return
        walk(ctx, host, remotePath, localDir, token)
        HistoryDb.get(ctx).updateMirrorScan(host, remotePath, System.currentTimeMillis())
    }

    private fun walk(ctx: Context, host: String, remote: String, local: File, token: String) {
        local.mkdirs()
        val items = listRemote(host, remote, token) ?: return
        for (i in 0 until items.length()) {
            val o = items.getJSONObject(i)
            val name = o.getString("n")
            val isDir = o.getBoolean("d")
            val size = o.getLong("s")
            val remoteChild = if (remote.isEmpty()) name else "$remote/$name"
            val localChild = File(local, name)
            if (isDir) {
                walk(ctx, host, remoteChild, localChild, token)
            } else {
                val already = localChild.exists() && localChild.length() == size
                if (!already) HistoryDb.get(ctx).enqueue(host, remoteChild, name, size)
            }
        }
    }

    private fun listRemote(host: String, path: String, token: String): JSONArray? {
        return try {
            val url = Net.url(host, "list", path, "", "", token)
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 5000
            c.readTimeout = 15000
            val body = c.inputStream.bufferedReader().use { it.readText() }
            JSONArray(body)
        } catch (e: Exception) { null }
    }
}
