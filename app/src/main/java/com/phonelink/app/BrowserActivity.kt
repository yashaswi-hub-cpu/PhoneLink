package com.phonelink.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.provider.OpenableColumns
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** 403 from the other phone: the PIN / login token is no longer valid. */
class AuthException(m: String) : IOException(m)

class BrowserActivity : Activity() {
    class Item(val name: String, val dir: Boolean, val size: Long)

    @Volatile private var host = ""
    @Volatile private var token = ""      // PIN, or the login token of a paired phone
    private var sid = ""                  // id of the paired phone ("" = connected with PIN only)
    private var sname = ""
    private var path = ""
    private var want = ""
    private var shown: String? = null
    private var items: List<Item> = emptyList()
    private val cache = ConcurrentHashMap<String, List<Item>>()
    private val pool = Executors.newCachedThreadPool()
    private val authLock = Any()
    private val protRx = Regex("(^|/)Android/(data|obb)(/|$)", RegexOption.IGNORE_CASE)
    private lateinit var pathView: TextView
    private lateinit var status: TextView
    private lateinit var lv: ListView
    private lateinit var ad: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lan.start(applicationContext)
        System.setProperty("http.maxConnections", "16")
        host = intent.getStringExtra("host") ?: ""
        token = intent.getStringExtra("pin") ?: ""
        sid = intent.getStringExtra("sid") ?: ""
        sname = intent.getStringExtra("sname") ?: host
        title = if (sname.isEmpty()) host else sname

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL

        pathView = TextView(this)
        pathView.textSize = 15f
        pathView.setPadding(dp(12), dp(8), dp(12), dp(2))
        status = TextView(this)
        status.textSize = 13f
        status.setPadding(dp(12), 0, dp(12), dp(6))
        status.text = "Connecting..."

        val back = Button(this)
        back.text = "Back"
        back.setOnClickListener { goUp() }
        val send = Button(this)
        send.text = "Send file here"
        send.setOnClickListener { pickFiles() }
        val hist = Button(this)
        hist.text = "History"
        hist.setOnClickListener { startActivity(Intent(this, HistoryActivity::class.java)) }
        val bar = LinearLayout(this)
        bar.addView(back, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(send, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f))
        bar.addView(hist, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        ad = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList<String>())
        lv = ListView(this)
        lv.adapter = ad
        lv.isFastScrollEnabled = true
        lv.setOnItemClickListener { _, _, i, _ -> if (i < items.size) open(items[i]) }

        col.addView(pathView)
        col.addView(status)
        col.addView(bar)
        col.addView(lv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(col)
        load("")
    }

    private fun toast(m: String) = runOnUiThread { Toast.makeText(this, m, Toast.LENGTH_SHORT).show() }
    private fun setStatus(t: String) = runOnUiThread { status.text = t }

    private fun conn(ep: String, p: String, extra: String = ""): HttpURLConnection {
        val c = Lan.open(URL(Net.url(host, ep, p, token, extra))) as HttpURLConnection
        c.connectTimeout = 4000
        c.readTimeout = 20000
        c.setRequestProperty("Accept-Encoding", "identity")
        return c
    }

    private fun errText(c: HttpURLConnection): String =
        try {
            c.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP ${c.responseCode}"
        } catch (e: Exception) {
            "HTTP ${c.responseCode}"
        }

    /** Throws the right exception for a non-OK answer. */
    private fun fail(c: HttpURLConnection): Nothing {
        val code = c.responseCode
        val t = errText(c)
        if (code == 403) throw AuthException(t)
        throw IOException(t)
    }

    // ---- connection recovery: new IP / new login token / server restarted ----
    private fun authOk(): Boolean = try {
        val c = conn("ping", "")
        c.connectTimeout = 2000
        c.responseCode == 200
    } catch (e: Exception) {
        false
    }

    private fun reconnect(oldToken: String): Boolean {
        return synchronized(authLock) {
            if (token != oldToken) return@synchronized true   // another thread already fixed it
            if (authOk()) return@synchronized true
            if (sid.isEmpty()) return@synchronized false      // PIN-only: cannot log in again by itself
            setStatus("Reconnecting...")
            val ses = Connector.relogin(applicationContext, sid, host) ?: return@synchronized false
            host = ses.host
            token = ses.token
            true
        }
    }

    private fun <T> retry(times: Int, block: () -> T): T {
        var last: Exception? = null
        for (i in 0 until times) {
            val t = token
            try {
                return block()
            } catch (e: Exception) {
                last = e
                if (i == times - 1) break
                setStatus("Reconnecting...")
                try {
                    Thread.sleep(300L * (i + 1))
                } catch (ie: InterruptedException) {
                    break
                }
                if (e is AuthException || i >= 1) reconnect(t)
            }
        }
        throw last ?: IOException("failed")
    }

    // ---- browsing (cached: instant back/forward, refreshed in background) ----
    private fun show(p: String, list: List<Item>) {
        items = list
        path = p
        ad.setNotifyOnChange(false)
        ad.clear()
        ad.addAll(list.map { if (it.dir) "[+]  ${it.name}" else "${it.name}  (${fmtSize(it.size)})" })
        ad.notifyDataSetChanged()
        pathView.text = "/$p"
        if (shown != p) lv.setSelection(0)
        shown = p
    }

    private fun load(p: String) {
        want = p
        cache[p]?.let { show(p, it) }
        pool.execute {
            try {
                val res = retry(3) {
                    val c = conn("list", p)
                    if (c.responseCode != 200) fail(c)
                    val arr = JSONArray(c.inputStream.bufferedReader().use { it.readText() })
                    val out = ArrayList<Item>(arr.length())
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        out.add(Item(o.getString("n"), o.getBoolean("d"), o.getLong("s")))
                    }
                    out
                }
                cache[p] = res
                runOnUiThread {
                    if (want == p) show(p, res)
                    status.text = "Connected"
                }
            } catch (e: Exception) {
                setStatus("Not connected")
                toast("Failed: ${e.message}")
            }
        }
    }

    private fun goUp() {
        if (path.isEmpty()) finish() else load(path.substringBeforeLast('/', ""))
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = goUp()

    private fun open(item: Item) {
        val rel = if (path.isEmpty()) item.name else "$path/${item.name}"
        if (item.dir) {
            load(rel)
            return
        }
        val e = extOf(item.name)
        val canView = e in VIDEO || e in AUDIO || e in IMAGE
        val opts = if (canView) arrayOf("Play / View", "Download") else arrayOf("Download")
        AlertDialog.Builder(this).setTitle(item.name).setItems(opts) { _, w ->
            if (canView && w == 0) {
                History.add(applicationContext, "play", item.name, item.size, true, 0.0, sname, "")
                startActivity(
                    Intent(this, PlayerActivity::class.java)
                        .putExtra("url", Net.url(host, "file", rel, token))
                        .putExtra("name", item.name)
                )
            } else download(rel, item.name, item.size)
        }.show()
    }

    // ---- fast transfers: many parallel connections, work-queue of blocks, auto-retry ----
    @Suppress("DEPRECATION")
    private fun wifiMode(): Int =
        if (android.os.Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        else WifiManager.WIFI_MODE_FULL_HIGH_PERF

    private inline fun <T> locked(block: () -> T): T {
        val wl = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(wifiMode(), "phonelink:xfer")
        val pw = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "phonelink:xfer")
        wl.acquire()
        pw.acquire(30 * 60 * 1000L)
        try {
            return block()
        } finally {
            if (wl.isHeld) wl.release()
            if (pw.isHeld) pw.release()
        }
    }

    private fun mbps(bytes: Long, t0: Long): Double {
        val sec = maxOf(0.001, (System.nanoTime() - t0) / 1e9)
        return bytes / 1048576.0 / sec
    }

    /** Runs [workers] threads; each takes the next free block until all blocks are done. */
    private fun transfer(
        workers: Int, blocks: Int, label: String, total: Long, done: AtomicLong, t0: Long,
        job: (Int, AtomicBoolean) -> Unit
    ) {
        val next = AtomicInteger(0)
        val abort = AtomicBoolean(false)
        val err = AtomicReference<Exception?>(null)
        val latch = CountDownLatch(workers)
        for (w in 0 until workers) {
            pool.execute {
                try {
                    while (!abort.get()) {
                        val b = next.getAndIncrement()
                        if (b >= blocks) break
                        job(b, abort)
                    }
                } catch (ex: Exception) {
                    err.compareAndSet(null, ex)
                    abort.set(true)
                } finally {
                    latch.countDown()
                }
            }
        }
        while (!latch.await(400, TimeUnit.MILLISECONDS)) {
            val d = done.get()
            val pct = if (total > 0) d * 100 / total else 0
            setStatus("$label  $pct%  ${fmtSpeed(mbps(d, t0))}")
        }
        err.get()?.let { throw it }
    }

    private fun download(rel: String, name: String, size: Long) {
        pool.execute {
            var target: File? = null
            val t0 = System.nanoTime()
            val done = AtomicLong(0)
            try {
                locked {
                    val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    dir.mkdirs()
                    var t = File(dir, name)
                    if (t.exists()) t = File(dir, "${System.currentTimeMillis()}_$name")
                    target = t
                    if (size == 0L) {
                        t.createNewFile()
                    } else {
                        RandomAccessFile(t, "rw").use { raf ->
                            raf.setLength(size)
                            val ch = raf.channel
                            val blocks = ((size + BLOCK - 1) / BLOCK).toInt()
                            transfer(minOf(WORKERS, blocks), blocks, "Down $name", size, done, t0) { b, abort ->
                                val s = b * BLOCK
                                fetchRange(rel, s, minOf(size - 1, s + BLOCK - 1), ch, done, abort)
                            }
                        }
                    }
                }
                val sp = mbps(done.get(), t0)
                setStatus("Saved Downloads/${target?.name}  avg ${fmtSpeed(sp)}")
                History.add(applicationContext, "down", name, size, true, sp, sname, target?.absolutePath ?: "")
            } catch (e: Exception) {
                target?.delete()
                setStatus("Download failed")
                toast("Download failed: ${e.message}")
                History.add(applicationContext, "down", name, size, false, 0.0, sname, "")
            }
        }
    }

    /** Downloads bytes s..e into the file. Survives dropped connections: continues from where it stopped. */
    private fun fetchRange(rel: String, s: Long, e: Long, ch: FileChannel, done: AtomicLong, abort: AtomicBoolean) {
        var pos = s
        var tries = 0
        val buf = ByteArray(1 shl 20)
        while (pos <= e) {
            if (abort.get()) throw IOException("cancelled")
            val before = pos
            val t = token
            try {
                val c = conn("file", rel)
                c.setRequestProperty("Range", "bytes=$pos-$e")
                if (c.responseCode != 206) fail(c)
                c.inputStream.use { ins ->
                    while (pos <= e) {
                        val n = ins.read(buf, 0, minOf(buf.size.toLong(), e - pos + 1).toInt())
                        if (n < 0) break
                        val bb = ByteBuffer.wrap(buf, 0, n)
                        var p = pos
                        while (bb.hasRemaining()) p += ch.write(bb, p)
                        pos += n
                        done.addAndGet(n.toLong())
                    }
                }
                if (pos <= e) throw IOException("Connection dropped")
            } catch (ex: IOException) {
                if (pos > before) tries = 0
                if (++tries > 8 || abort.get()) throw ex
                try {
                    Thread.sleep(minOf(3000L, 300L * tries))
                } catch (ie: InterruptedException) {
                    throw ex
                }
                if (ex is AuthException || tries >= 2) reconnect(t)
            }
        }
    }

    // ---- sending files to the other phone ----
    private fun pickFiles() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
        i.addCategory(Intent.CATEGORY_OPENABLE)
        i.type = "*/*"
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        startActivityForResult(i, 7)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 7 || resultCode != RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        val clip = data.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
        } else data.data?.let { uris.add(it) }
        val target = path
        pool.execute {
            for (u in uris) {
                try {
                    upload(u, target)
                } catch (e: Exception) {
                    toast("Upload failed: ${e.message}")
                }
            }
            runOnUiThread { load(target) }
        }
    }

    private fun upload(u: Uri, dir: String) {
        var name = "file"
        var size = -1L
        contentResolver.query(u, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0) name = c.getString(ni)
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
        if (size < 0) throw Exception("unknown size")
        val fname = name
        val total = size
        val t0 = System.nanoTime()
        val done = AtomicLong(0)
        val prot = protRx.containsMatchIn(dir)
        try {
            locked {
                if (total < (8L shl 20) || prot) {
                    retry(3) {
                        done.set(0)
                        sendPart(u, dir, fname, 0L, total, total, false, done)
                    }
                } else {
                    val blocks = ((total + UBLOCK - 1) / UBLOCK).toInt()
                    transfer(minOf(WORKERS, blocks), blocks, "Up $fname", total, done, t0) { b, abort ->
                        val s = b * UBLOCK
                        val len = minOf(UBLOCK, total - s)
                        retry(5) {
                            if (abort.get()) throw IOException("cancelled")
                            sendPart(u, dir, fname, s, len, total, true, done)
                        }
                    }
                }
            }
            val sp = mbps(total, t0)
            setStatus("Sent $fname  avg ${fmtSpeed(sp)}")
            History.add(applicationContext, "up", fname, total, true, sp, sname, "")
        } catch (e: Exception) {
            History.add(applicationContext, "up", fname, total, false, 0.0, sname, "")
            throw e
        }
    }

    private fun sendPart(
        u: Uri, dir: String, name: String, off: Long, len: Long, total: Long, ranged: Boolean, done: AtomicLong
    ) {
        val extra = "&name=${Net.enc(name)}" + if (ranged) "&offset=$off&total=$total" else ""
        val c = conn("upload", dir, extra)
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/octet-stream")
        c.setFixedLengthStreamingMode(len)
        c.readTimeout = 120000

        var pfd: ParcelFileDescriptor? = null
        val ins: InputStream = if (ranged) {
            val d = contentResolver.openFileDescriptor(u, "r") ?: throw IOException("cannot open file")
            pfd = d
            FileInputStream(d.fileDescriptor).also { it.channel.position(off) }
        } else {
            contentResolver.openInputStream(u) ?: throw IOException("cannot open file")
        }
        var sent = 0L
        try {
            c.outputStream.use { o ->
                val buf = ByteArray(1 shl 20)
                var left = len
                while (left > 0) {
                    val n = ins.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) throw IOException("read failed")
                    o.write(buf, 0, n)
                    left -= n
                    sent += n
                    done.addAndGet(n.toLong())
                }
            }
            if (c.responseCode != 200) fail(c)
        } catch (e: Exception) {
            done.addAndGet(-sent)   // retry will count these bytes again
            throw e
        } finally {
            ins.close()
            pfd?.close()
        }
    }
}
