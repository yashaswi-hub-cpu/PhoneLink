package com.phonelink.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.webkit.MimeTypeMap
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile

class FileServerService : Service() {
    companion object {
        @Volatile var running = false
        @Volatile var pin = ""
        @Volatile var useTls = false
    }

    private var server: Server? = null
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        pin = intent?.getStringExtra("pin") ?: pin

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("srv", "PhoneLink server", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, "srv")
            .setContentTitle("PhoneLink is sharing storage")
            .setContentText("Port $PORT")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(
                PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE)
            )
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, n)
        }

        if (server == null) {
            try {
                server = Server(applicationContext, pin).also { it.start(60000, false) }
                wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "phonelink:srv").also { it.acquire() }
                @Suppress("DEPRECATION")
                wifi = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                    .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "phonelink").also { it.acquire() }
                running = true
            } catch (e: Exception) {
                running = false
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        ShizukuBridge.release()
        wake?.let { if (it.isHeld) it.release() }
        wifi?.let { if (it.isHeld) it.release() }
        running = false
        super.onDestroy()
    }
}

class Server(private val ctx: Context, private val pin: String) : NanoHTTPD(PORT) {
    private class Entry(val n: String, val d: Boolean, val s: Long)

    private val primary: File = Environment.getExternalStorageDirectory()
    @Volatile private var fails = 0
    @Volatile private var volCache: List<Pair<String, File>> = emptyList()
    @Volatile private var volAt = 0L
    private val protRx = Regex("^/storage/[^/]+(/\\d+)?/Android/(data|obb)(/.*)?$")
    private val cmp = Comparator<Entry> { a, b ->
        if (a.d != b.d) (if (a.d) -1 else 1) else String.CASE_INSENSITIVE_ORDER.compare(a.n, b.n)
    }

    override fun serve(session: IHTTPSession): Response {
        val q = session.parameters
        fun p(k: String): String = q[k]?.firstOrNull() ?: ""

        if (fails >= 20) return msg(Status.FORBIDDEN, "Locked. Restart sharing.")

        val pinOk = p("t") == pin && pin.isNotEmpty()

        val tokenOk = run {
            val raw = p("raw")
            if (raw.isEmpty()) return@run false
            val deviceId = p("d")
            val ts = p("ts").toLongOrNull() ?: 0L
            val sig = p("s")
            Pairing.Server.verifySig(ctx, raw, deviceId, ts, p("path"), sig)
        }

        if (!pinOk && !tokenOk) {
            fails++
            return msg(Status.FORBIDDEN, if (p("t").isNotEmpty()) "Wrong PIN" else "Auth required")
        }

        return try {
            when (session.uri) {
                "/list" -> {
                    val r = list(p("path"))
                    if (pinOk && !tokenOk) {
                        val tok = Pairing.Server.issueToken(ctx, "client-${System.currentTimeMillis() % 100000}")
                        r.addHeader("X-Pair-Token", tok)
                    }
                    r
                }
                "/file" -> serveFile(p("path"), session.headers["range"])
                "/upload" -> upload(session, p("path"), p("name"),
                    p("offset").toLongOrNull(), p("total").toLongOrNull())
                "/devices" -> {
                    if (!pinOk) return msg(Status.FORBIDDEN, "PIN required")
                    msg(Status.OK, Pairing.Server.list(ctx).toString())
                }
                "/revoke" -> {
                    if (!pinOk) return msg(Status.FORBIDDEN, "PIN required")
                    val id = p("id")
                    if (id.isEmpty()) Pairing.Server.revokeAll(ctx) else Pairing.Server.revoke(ctx, id)
                    msg(Status.OK, "ok")
                }
                else -> msg(Status.NOT_FOUND, "Not found")
            }
        } catch (e: Exception) {
            msg(Status.INTERNAL_ERROR, e.message ?: "error")
        }
    }

    private fun msg(s: Status, m: String): Response {
        val r = newFixedLengthResponse(s, "text/plain", m)
        if (s != Status.OK) r.closeConnection(true)
        return r
    }

    private fun volumes(): List<Pair<String, File>> {
        val now = System.currentTimeMillis()
        if (now - volAt < 5000 && volCache.isNotEmpty()) return volCache
        val out = ArrayList<Pair<String, File>>()
        out.add("Internal storage" to primary)
        var n = 0
        for (d in ctx.getExternalFilesDirs(null)) {
            if (d == null) continue
            val root = File(d.absolutePath.substringBefore("/Android/data"))
            if (root.absolutePath == primary.absolutePath || !root.isDirectory) continue
            n++
            out.add((if (n == 1) "SD card" else "SD card $n") to root)
        }
        volCache = out
        volAt = now
        return out
    }

    private fun resolve(rel: String): File? {
        val clean = rel.trim('/')
        if (clean.isEmpty()) return null
        val first = clean.substringBefore('/')
        val rest = clean.substringAfter('/', "")
        val base = volumes().firstOrNull { it.first == first }?.second
            ?: throw IllegalStateException("Unknown storage: $first")
        val f = File(base, rest).canonicalFile
        val b = base.canonicalPath
        if (f.path != b && !f.path.startsWith("$b/")) throw SecurityException("Outside storage")
        return f
    }

    private fun isProtected(f: File): Boolean =
        Build.VERSION.SDK_INT >= 30 && protRx.matches(f.path)

    private fun needShizuku() {
        if (!ShizukuBridge.ready())
            throw IllegalStateException("Android/data needs Shizuku: start Shizuku and tap 'Enable Android/data'")
    }

    private fun fileLen(f: File, prot: Boolean): Long {
        if (prot) {
            val s = ShizukuBridge.stat(f.path)
            return if (s.startsWith("f")) s.substring(1).toLong() else -1L
        }
        return if (f.isFile) f.length() else -1L
    }

    private fun openIn(f: File, prot: Boolean): FileInputStream =
        if (prot) ParcelFileDescriptor.AutoCloseInputStream(ShizukuBridge.openRead(f.path))
        else FileInputStream(f)

    private fun entries(f: File): List<Entry> {
        if (isProtected(f)) {
            needShizuku()
            val arr = JSONArray(ShizukuBridge.list(f.path))
            return (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Entry(o.getString("n"), o.getBoolean("d"), o.getLong("s"))
            }
        }
        val files = f.listFiles() ?: return emptyList()
        return files.map { val d = it.isDirectory; Entry(it.name, d, if (d) 0L else it.length()) }
    }

    private fun list(rel: String): Response {
        val d = resolve(rel)
        val items = if (d == null) volumes().map { Entry(it.first, true, 0L) } else entries(d)
        val arr = JSONArray()
        for (e in items.sortedWith(cmp)) arr.put(JSONObject().put("n", e.n).put("d", e.d).put("s", e.s))
        return newFixedLengthResponse(Status.OK, "application/json", arr.toString())
    }

    private fun serveFile(rel: String, range: String?): Response {
        val f = resolve(rel) ?: return msg(Status.NOT_FOUND, "No such file")
        val prot = isProtected(f)
        if (prot) needShizuku()
        val len = fileLen(f, prot)
        if (len < 0) return msg(Status.NOT_FOUND, "No such file")
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase())
            ?: "application/octet-stream"
        if (len == 0L) return newFixedLengthResponse(Status.OK, mime, "")

        var start = 0L
        var end = len - 1
        var partial = false
        if (range != null && range.startsWith("bytes=")) {
            val parts = range.removePrefix("bytes=").split("-")
            if (parts[0].isEmpty()) {
                val suffix = parts.getOrNull(1)?.toLongOrNull() ?: 0L
                start = maxOf(0L, len - suffix)
            } else {
                start = parts[0].toLongOrNull() ?: 0L
                parts.getOrNull(1)?.toLongOrNull()?.let { end = minOf(it, len - 1) }
            }
            partial = true
        }
        if (start >= len || start > end) {
            val r = msg(Status.RANGE_NOT_SATISFIABLE, "range")
            r.addHeader("Content-Range", "bytes */$len")
            return r
        }
        val ins = openIn(f, prot)
        try { ins.channel.position(start) } catch (e: Exception) { ins.close(); throw e }
        val buffered = BufferedInputStream(ins, 1 shl 20)
        val res = newFixedLengthResponse(
            if (partial) Status.PARTIAL_CONTENT else Status.OK, mime, buffered, end - start + 1
        )
        res.addHeader("Accept-Ranges", "bytes")
        if (partial) res.addHeader("Content-Range", "bytes $start-$end/$len")
        return res
    }

    private fun pump(ins: InputStream, len: Long, write: (ByteArray, Int) -> Unit): Long {
        var left = len
        val buf = ByteArray(1 shl 20)
        while (left > 0) {
            val n = ins.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) break
            write(buf, n)
            left -= n
        }
        return left
    }

    private fun upload(s: IHTTPSession, rel: String, name: String, off: Long?, total: Long?): Response {
        val safe = File(name).name
        if (safe.isEmpty() || safe == "." || safe == "..") return msg(Status.BAD_REQUEST, "name")
        val len = s.headers["content-length"]?.toLongOrNull() ?: return msg(Status.BAD_REQUEST, "length")
        val dir = resolve(rel) ?: return msg(Status.BAD_REQUEST, "Open a folder first")
        val prot = isProtected(dir)
        if (prot) needShizuku() else if (!dir.isDirectory) return msg(Status.NOT_FOUND, "dir")
        val target = File(dir, safe)
        var left = len
        if (off == null) {
            val out: OutputStream =
                if (prot) ParcelFileDescriptor.AutoCloseOutputStream(ShizukuBridge.openWrite(target.path))
                else FileOutputStream(target)
            out.use { o -> left = pump(s.inputStream, len) { b, n -> o.write(b, 0, n) } }
            if (left > 0) target.delete()
        } else {
            if (prot) return msg(Status.BAD_REQUEST, "no parallel upload here")
            RandomAccessFile(target, "rw").use { raf ->
                if (total != null && raf.length() != total) raf.setLength(total)
                raf.seek(off)
                left = pump(s.inputStream, len) { b, n -> raf.write(b, 0, n) }
            }
        }
        if (left > 0) return msg(Status.BAD_REQUEST, "incomplete")
        return msg(Status.OK, "ok")
    }
}
