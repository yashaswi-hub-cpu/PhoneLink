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
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class FileServerService : Service() {
    companion object {
        @Volatile var running = false
        @Volatile var pin = ""
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
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
                )
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
                Trust.pending.clear()
                server = Server(applicationContext, pin).also { it.start(60000, false) }
                wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "phonelink:srv").also { it.acquire() }
                wifi = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                    .createWifiLock(wifiMode(), "phonelink").also { it.acquire() }
                running = true
            } catch (e: Exception) {
                running = false
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun wifiMode(): Int =
        if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        else WifiManager.WIFI_MODE_FULL_HIGH_PERF

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
    @Volatile private var lastSent = ""
    @Volatile private var lastSentAt = 0L
    private val tokens = ConcurrentHashMap<String, String>()   // login token -> phone name
    private val nonces = ConcurrentHashMap<String, Long>()     // one-time challenges
    private val protRx = Regex("^/storage/[^/]+(/\\d+)?/Android/(data|obb)(/.*)?$")
    private val cmp = Comparator<Entry> { a, b ->
        if (a.d != b.d) (if (a.d) -1 else 1) else String.CASE_INSENSITIVE_ORDER.compare(a.n, b.n)
    }

    private fun Map<String, List<String>>.p(k: String): String = this[k]?.firstOrNull() ?: ""

    override fun serve(session: IHTTPSession): Response = try {
        route(session, session.parameters)
    } catch (e: SecurityException) {
        msg(Status.FORBIDDEN, e.message ?: "denied")
    } catch (e: Exception) {
        msg(Status.INTERNAL_ERROR, e.message ?: "error")
    }

    private fun route(s: IHTTPSession, q: Map<String, List<String>>): Response {
        // open endpoints: who am I / challenge
        when (s.uri) {
            "/hello" -> return json(
                JSONObject().put("app", "phonelink").put("id", Store.deviceId(ctx))
                    .put("name", Store.deviceName()).toString()
            )
            "/challenge" -> return challenge()
        }
        if (fails >= 20) return msg(Status.FORBIDDEN, "Locked. Restart sharing.")
        // login endpoints
        when (s.uri) {
            "/pair" -> return pair(q.p("t"), q.p("id"), q.p("name"), q.p("pk"))
            "/auth" -> return auth(q.p("id"), q.p("nonce"), q.p("sig"))
        }
        // everything else needs the PIN or a login token of a paired phone
        val t = q.p("t")
        val who = if (t.isNotEmpty() && t == pin) "PIN" else tokens[t]
        if (who == null) {
            fails++
            return msg(Status.FORBIDDEN, "Wrong PIN")
        }
        return when (s.uri) {
            "/ping" -> msg(Status.OK, "ok")
            "/list" -> list(q.p("path"))
            "/file" -> serveFile(q.p("path"), s.headers["range"], who)
            "/upload" -> upload(s, q.p("path"), q.p("name"), q.p("offset").toLongOrNull(), q.p("total").toLongOrNull(), who)
            else -> msg(Status.NOT_FOUND, "Not found")
        }
    }

    private fun msg(s: Status, m: String): Response {
        val r = newFixedLengthResponse(s, "text/plain", m)
        if (s != Status.OK) r.closeConnection(true)
        return r
    }

    private fun json(s: String): Response = newFixedLengthResponse(Status.OK, "application/json", s)

    private fun log(kind: String, name: String, peer: String = "", size: Long = 0L) {
        History.add(ctx, kind, name, size, true, 0.0, peer, "")
    }

    // ---- pairing + login of remembered phones ----
    private fun challenge(): Response {
        val now = System.currentTimeMillis()
        nonces.entries.removeIf { now - it.value > 30000L }
        if (nonces.size > 200) nonces.clear()
        val n = Crypto.rand(16)
        nonces[n] = now
        return msg(Status.OK, n)
    }

    private fun auth(id: String, nonce: String, sig: String): Response {
        val at = nonces.remove(nonce)
        if (at == null || System.currentTimeMillis() - at > 30000L) {
            fails++
            return msg(Status.FORBIDDEN, "Try again")
        }
        val dev = Trust.client(ctx, id)
        if (dev == null) {
            fails++
            return msg(Status.FORBIDDEN, "This phone is not allowed here. Forget it and connect again with IP and PIN.")
        }
        val want = Crypto.hex(Crypto.hmac(dev.secret, "c|$nonce|$id"))
        if (!Crypto.same(want, sig)) {
            fails++
            return msg(Status.FORBIDDEN, "Verification failed")
        }
        val token = Crypto.rand(16)
        tokens[token] = dev.name
        log("conn", dev.name)
        val proof = Crypto.hex(Crypto.hmac(dev.secret, "s|$nonce|$token"))
        return json(JSONObject().put("token", token).put("proof", proof).toString())
    }

    private fun pair(t: String, id: String, name: String, pk: String): Response {
        if (t != pin) {
            fails++
            return msg(Status.FORBIDDEN, "Wrong PIN")
        }
        if (id.isEmpty() || pk.isEmpty()) return msg(Status.BAD_REQUEST, "bad request")
        val clientKey = Crypto.unb64(pk)
        val req = PairReq(id, name.take(40).ifEmpty { "Phone" })
        Trust.pending.add(req)
        notifyPair(req.name)
        // the owner of THIS phone must tap Allow
        val answered = try {
            req.latch.await(60, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            false
        }
        Trust.pending.remove(req)
        if (!answered || !req.ok) return msg(Status.FORBIDDEN, "Not allowed on the other phone")
        val kp = Crypto.newKeyPair()
        val secret = Crypto.agree(kp.private, clientKey)
        Trust.addClient(ctx, id, req.name, secret)
        log("pair", req.name)
        return json(
            JSONObject().put("sid", Store.deviceId(ctx)).put("sname", Store.deviceName())
                .put("pk", Crypto.b64(kp.public.encoded)).toString()
        )
    }

    private fun notifyPair(name: String) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel("pair", "Pairing requests", NotificationManager.IMPORTANCE_HIGH)
            )
            val pi = PendingIntent.getActivity(
                ctx, 2,
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = Notification.Builder(ctx, "pair")
                .setContentTitle("Allow $name?")
                .setContentText("It wants to connect without IP and PIN. Tap to answer.")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(2, n)
        } catch (e: Exception) {
        }
    }

    // ---- storage volumes (internal + SD card) ----
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

    /** null = virtual root (list of volumes) */
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

    // ---- Android/data and Android/obb go through Shizuku ----
    private fun isProtected(f: File): Boolean = Build.VERSION.SDK_INT >= 30 && protRx.matches(f.path)

    private fun needShizuku() {
        if (!ShizukuBridge.ready())
            throw IllegalStateException("Android/data needs Shizuku: start Shizuku and tap 'Enable Android/data' on that phone")
    }

    private fun fileLen(f: File, prot: Boolean): Long {
        if (prot) {
            val s = ShizukuBridge.stat(f.path)
            return if (s.startsWith("f")) s.substring(1).toLong() else -1L
        }
        return if (f.isFile) f.length() else -1L
    }

    private fun openIn(f: File, prot: Boolean): FileInputStream =
        if (prot) ParcelFileDescriptor.AutoCloseInputStream(ShizukuBridge.openRead(f.path)) else FileInputStream(f)

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
        return files.map {
            val d = it.isDirectory
            Entry(it.name, d, if (d) 0L else it.length())
        }
    }

    private fun list(rel: String): Response {
        val d = resolve(rel)
        val items = if (d == null) volumes().map { Entry(it.first, true, 0L) } else entries(d)
        val arr = JSONArray()
        for (e in items.sortedWith(cmp)) arr.put(JSONObject().put("n", e.n).put("d", e.d).put("s", e.s))
        return newFixedLengthResponse(Status.OK, "application/json", arr.toString())
    }

    private fun logSent(name: String, who: String, size: Long) {
        val key = "$who|$name"
        val now = System.currentTimeMillis()
        if (key == lastSent && now - lastSentAt < 15000L) return
        lastSent = key
        lastSentAt = now
        log("sent", name, who, size)
    }

    private fun serveFile(rel: String, range: String?, who: String): Response {
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
        try {
            ins.channel.position(start)
        } catch (e: Exception) {
            ins.close()
            throw e
        }
        if (start == 0L) logSent(f.name, who, len)
        val res = newFixedLengthResponse(
            if (partial) Status.PARTIAL_CONTENT else Status.OK, mime, ins, end - start + 1
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

    private fun upload(s: IHTTPSession, rel: String, name: String, off: Long?, total: Long?, who: String): Response {
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
        if (off == null) log("recv", safe, who, len)
        else if (total != null && off + len >= total) log("recv", safe, who, total)
        return msg(Status.OK, "ok")
    }
}
