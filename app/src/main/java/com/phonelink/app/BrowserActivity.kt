package com.phonelink.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class BrowserActivity : Activity() {
    class Item(val name: String, val dir: Boolean, val size: Long)

    private var host = ""
    private var pin = ""
    private var path = ""
    private var want = ""
    private var shown: String? = null
    private var items: List<Item> = emptyList()
    private val cache = ConcurrentHashMap<String, List<Item>>()
    private val pool = Executors.newCachedThreadPool()
    private lateinit var pathView: TextView
    private lateinit var status: TextView
    private lateinit var lv: ListView
    private lateinit var ad: ArrayAdapter<String>
    private lateinit var db: HistoryDb
    private var token: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host = intent.getStringExtra("host") ?: ""
        pin = intent.getStringExtra("pin") ?: ""
        title = host
        db = HistoryDb.get(this)
        token = Pairing.getToken(this, host)

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL

        pathView = TextView(this)
        pathView.textSize = 15f
        pathView.setPadding(dp(12), dp(8), dp(12), dp(2))
        status = TextView(this)
        status.textSize = 13f
        status.setPadding(dp(12), 0, dp(12), dp(6))

        val back = Button(this)
        back.text = "Back"
        back.setOnClickListener { goUp() }
        val send = Button(this)
        send.text = "Send file here"
        send.setOnClickListener { pickFiles() }
        val bar = LinearLayout(this)
        bar.addView(back, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(send, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        ad = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList<String>())
        lv = ListView(this)
        lv.adapter = ad
        lv.isFastScrollEnabled = true
        lv.setOnItemClickListener { _, _, i, _ -> if (i < items.size) open(items[i]) }
        lv.setOnItemLongClickListener { _, _, i, _ ->
            if (i >= items.size) return@setOnItemLongClickListener false
            val it = items[i]
            val rel = if (path.isEmpty()) it.name else "$path/${it.name}"
            val opts = if (it.dir) arrayOf("Mirror this folder", "Queue this folder (recursive)")
                       else arrayOf("Queue for later", "Download now")
            AlertDialog.Builder(this).setTitle(it.name).setItems(opts) { _, w ->
                if (it.dir && w == 0) addMirror(rel)
                else if (it.dir && w == 1) queueFolder(rel)
                else if (!it.dir && w == 0) {
                    HistoryDb.get(this).enqueue(host, rel, it.name, it.size)
                    toast("Queued — will download when peer is online")
                } else if (!it.dir && w == 1) download(rel, it.name, it.size)
            }.show()
            true
        }

        col.addView(pathView)
        col.addView(status)
        col.addView(bar)
        col.addView(lv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(col)

        TransferBus.subscribe { ev ->
            if (ev.status == "running") {
                val pct = if (ev.size > 0) ev.bytesDone * 100 / ev.size else 0
                setStatus("${if (ev.direction == "down") "Down" else "Up"} ${ev.name}  $pct%  ${fmtSize(ev.bytesDone)}")
            } else if (ev.status == "done") setStatus("Done: ${ev.name}")
            else if (ev.status == "failed") setStatus("Failed: ${ev.name} ${ev.message ?: ""}")
        }

        load("")
    }

    private fun toast(m: String) = runOnUiThread { Toast.makeText(this, m, Toast.LENGTH_SHORT).show() }
    private fun setStatus(t: String) = runOnUiThread { status.text = t }

    private fun conn(ep: String, p: String, extra: String = ""): java.net.HttpURLConnection {
        val url = Net.url(host, ep, p, pin, extra, token)
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 5000
        c.readTimeout = 30000
        c.setRequestProperty("Accept-Encoding", "identity")
        return c
    }

    private fun errText(c: java.net.HttpURLConnection): String =
        try { c.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP ${c.responseCode}" }
        catch (e: Exception) { "HTTP ${c.responseCode}" }

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

        if (cache[p] == null) {
            db.getCachedFolder(host, p)?.let { (json, at) ->
                val res = parseList(json)
                if (res != null) {
                    cache[p] = res
                    show(p, res)
                    setStatus("offline copy — ${ageText(at)}")
                }
            }
        }

        pool.execute {
            try {
                val c = conn("list", p)
                if (c.responseCode != 200) throw Exception(errText(c))
                if (token == null) {
                    c.getHeaderField("X-Pair-Token")?.let { tok ->
                        token = tok
                        Pairing.saveToken(this@BrowserActivity, host, tok, "device-${host.takeLast(5)}")
                        runOnUiThread { toast("Paired — PIN no longer needed") }
                    }
                }
                val body = c.inputStream.bufferedReader().use { it.readText() }
                val res = parseList(body) ?: throw Exception("bad json")
                cache[p] = res
                db.cacheFolder(host, p, body)
                runOnUiThread {
                    if (want == p) { show(p, res); status.text = "" }
                }
            } catch (e: Exception) {
                if (cache[p] == null) runOnUiThread { toast("Offline — no cached copy of /$p") }
            }
        }
    }

    private fun parseList(json: String): List<Item>? = try {
        val arr = JSONArray(json)
        val res = ArrayList<Item>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            res.add(Item(o.getString("n"), o.getBoolean("d"), o.getLong("s")))
        }
        res
    } catch (e: Exception) { null }

    private fun ageText(then: Long): String {
        val s = (System.currentTimeMillis() - then) / 1000
        return when {
            s < 60 -> "${s}s old"
            s < 3600 -> "${s / 60}m old"
            s < 86400 -> "${s / 3600}h old"
            else -> "${s / 86400}d old"
        }
    }

    private fun goUp() {
        if (path.isEmpty()) finish() else load(path.substringBeforeLast('/', ""))
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = goUp()

    private fun open(item: Item) {
        val rel = if (path.isEmpty()) item.name else "$path/${item.name}"
        if (item.dir) { load(rel); return }
        val e = extOf(item.name)
        val canView = e in VIDEO || e in AUDIO || e in IMAGE
        val opts = if (canView) arrayOf("Play / View", "Download") else arrayOf("Download")
        AlertDialog.Builder(this).setTitle(item.name).setItems(opts) { _, w ->
            if (canView && w == 0) {
                startActivity(Intent(this, PlayerActivity::class.java)
                    .putExtra("url", Net.url(host, "file", rel, pin, "", token))
                    .putExtra("name", item.name))
            } else download(rel, item.name, item.size)
        }.show()
    }

    private fun download(rel: String, name: String, size: Long) {
        askBackground(name) { background ->
            val svc = Intent(this, TransferService::class.java)
                .setAction(TransferService.ACTION_DOWNLOAD)
                .putExtra(TransferService.EXTRA_HOST, host)
                .putExtra(TransferService.EXTRA_PIN, pin)
                .putExtra(TransferService.EXTRA_REL, rel)
                .putExtra(TransferService.EXTRA_NAME, name)
                .putExtra(TransferService.EXTRA_SIZE, size)
            if (background) {
                startForegroundService(svc)
                toast("Download continues in background")
            } else {
                startService(svc)
                toast("Download stops if you close the app")
            }
        }
    }

    private fun askBackground(name: String, onChoice: (Boolean) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(name)
            .setMessage("Continue this transfer if you close the app?")
            .setPositiveButton("D — Do it") { _, _ -> onChoice(true) }
            .setNegativeButton("N — No")   { _, _ -> onChoice(false) }
            .setCancelable(false)
            .show()
    }

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
        if (clip != null) { for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri) }
        else data.data?.let { uris.add(it) }
        val target = path
        for (u in uris) upload(u, target)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ load(target) }, 2000)
    }

    private fun upload(u: Uri, dir: String) {
        askBackground("Send file") { background ->
            val svc = Intent(this, TransferService::class.java)
                .setAction(TransferService.ACTION_UPLOAD)
                .putExtra(TransferService.EXTRA_HOST, host)
                .putExtra(TransferService.EXTRA_PIN, pin)
                .putExtra(TransferService.EXTRA_DIR, dir)
                .putExtra(TransferService.EXTRA_URI, u.toString())
            if (background) startForegroundService(svc) else startService(svc)
        }
    }

    private fun addMirror(remotePath: String) {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "PhoneLinkMirror/${host}/${remotePath.replace('/', '_')}"
        )
        dir.mkdirs()
        Mirror.add(this, host, remotePath, dir)
        toast("Mirror added → ${dir.name}")
    }

    private fun queueFolder(remotePath: String) {
        pool.execute {
            try {
                val c = conn("list", remotePath)
                val arr = JSONArray(c.inputStream.bufferedReader().use { it.readText() })
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val name = o.getString("n")
                    val isDir = o.getBoolean("d")
                    val size = o.getLong("s")
                    val rel = if (remotePath.isEmpty()) name else "$remotePath/$name"
                    if (isDir) queueFolder(rel)
                    else HistoryDb.get(this).enqueue(host, rel, name, size)
                }
                runOnUiThread { toast("Folder queued") }
            } catch (e: Exception) {
                runOnUiThread { toast("Queue failed: ${e.message}") }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pool.shutdownNow()
    }
}
