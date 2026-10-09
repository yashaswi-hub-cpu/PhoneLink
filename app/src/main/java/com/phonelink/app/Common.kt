package com.phonelink.app

import android.content.Context
import java.net.URLEncoder

const val PORT = 8765

object Net {
    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    fun url(
        host: String,
        ep: String,
        path: String,
        pin: String,
        extra: String = "",
        token: String? = null
    ): String {
        val scheme = if (FileServerService.useTls) "https" else "http"
        if (token == null) {
            return "$scheme://$host:$PORT/$ep?path=${enc(path)}&t=${enc(pin)}$extra"
        }
        val deviceId = Pairing.deviceIdOf(token)
        val ts = System.currentTimeMillis()
        val sig = Pairing.sign(token, deviceId, ts, path)
        return "$scheme://$host:$PORT/$ep?path=${enc(path)}" +
            "&d=$deviceId&ts=$ts&s=$sig&raw=${enc(token)}$extra"
    }
}

val VIDEO = setOf("mp4", "mkv", "webm", "3gp", "mov", "avi", "ts", "m4v")
val AUDIO = setOf("mp3", "m4a", "wav", "ogg", "aac", "flac", "opus")
val IMAGE = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")

fun extOf(name: String): String = name.substringAfterLast('.', "").lowercase()

fun fmtSize(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1048576 -> "${b / 1024} KB"
    b < 1073741824 -> "%.1f MB".format(b / 1048576.0)
    else -> "%.2f GB".format(b / 1073741824.0)
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
