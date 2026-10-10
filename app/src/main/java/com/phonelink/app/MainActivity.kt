package com.phonelink.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : Activity() {
    private lateinit var info: TextView
    private lateinit var toggle: Button
    private lateinit var trustBox: LinearLayout
    private lateinit var savedBox: LinearLayout
    private lateinit var connStatus: TextView
    private lateinit var ipBox: EditText
    private lateinit var pinBox: EditText
    private lateinit var remember: CheckBox
    private val ui = Handler(Looper.getMainLooper())
    private var asking = false
    private val tick = object : Runnable {
        override fun run() {
            checkPending()
            ui.postDelayed(this, 800)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Lan.start(applicationContext)
        title = "PhoneLink"
        val prefs = getSharedPreferences("p", MODE_PRIVATE)
        val pad = dp(16)

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(pad, pad, pad, pad)

        fun head(t: String) = TextView(this).also {
            it.text = t; it.textSize = 20f; it.setPadding(0, pad, 0, dp(6))
        }

        // ---------- this phone: share ----------
        col.addView(head("This phone: share"))
        val perm = Button(this)
        perm.text = "1. Allow storage access"
        perm.setOnClickListener { askStorage() }
        toggle = Button(this)
        toggle.setOnClickListener { toggleServer() }
        info = TextView(this)
        info.textSize = 16f
        val shz = Button(this)
        shz.text = "3. Enable Android/data (Shizuku)"
        shz.setOnClickListener { askShizuku() }
        trustBox = LinearLayout(this)
        trustBox.orientation = LinearLayout.VERTICAL
        col.addView(perm); col.addView(shz); col.addView(toggle); col.addView(info); col.addView(trustBox)

        // ---------- other phone: connect ----------
        col.addView(head("Other phone: connect"))
        connStatus = TextView(this)
        connStatus.textSize = 14f
        savedBox = LinearLayout(this)
        savedBox.orientation = LinearLayout.VERTICAL
        col.addView(connStatus); col.addView(savedBox)

        val newHead = TextView(this)
        newHead.text = "New phone (first time)"
        newHead.textSize = 16f
        newHead.setPadding(0, pad, 0, dp(4))
        ipBox = EditText(this)
        ipBox.hint = "Other phone IP (e.g. 192.168.1.5)"
        ipBox.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        ipBox.setText(prefs.getString("ip", ""))
        pinBox = EditText(this)
        pinBox.hint = "PIN shown on other phone"
        pinBox.inputType = InputType.TYPE_CLASS_NUMBER
        remember = CheckBox(this)
        remember.text = "Remember this phone (no IP / PIN next time)"
        remember.isChecked = true
        val go = Button(this)
        go.text = "Connect"
        go.setOnClickListener { connectNew(prefs) }
        col.addView(newHead); col.addView(ipBox); col.addView(pinBox); col.addView(remember); col.addView(go)

        // ---------- history ----------
        val hist = Button(this)
        hist.text = "History"
        hist.setOnClickListener { startActivity(Intent(this, HistoryActivity::class.java)) }
        col.addView(head("History"))
        col.addView(hist)

        val sv = ScrollView(this)
        sv.addView(col)
        setContentView(sv)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onResume() {
        super.onResume()
        refresh()
        ui.post(tick)
    }

    override fun onPause() {
        ui.removeCallbacks(tick)
        super.onPause()
    }

    // ---------- pairing approval (this is the "verified by the shared phone" step) ----------
    private fun checkPending() {
        if (asking) return
        val r = Trust.pending.firstOrNull() ?: return
        asking = true
        AlertDialog.Builder(this)
            .setTitle("Allow this phone?")
            .setMessage("${r.name} wants to connect without IP and PIN next time.\n\nAllow only your own phone.")
            .setCancelable(false)
            .setPositiveButton("Allow") { _, _ ->
                r.ok = true
                Trust.pending.remove(r)
                r.latch.countDown()
                asking = false
                refresh()
            }
            .setNegativeButton("Deny") { _, _ ->
                Trust.pending.remove(r)
                r.latch.countDown()
                asking = false
            }
            .show()
    }

    // ---------- connecting ----------
    private fun busy(t: String?) {
        connStatus.text = t ?: ""
    }

    private fun openBrowser(host: String, token: String, sid: String, name: String) {
        startActivity(
            Intent(this, BrowserActivity::class.java)
                .putExtra("host", host).putExtra("pin", token).putExtra("sid", sid).putExtra("sname", name)
        )
    }

    private fun connectNew(prefs: SharedPreferences) {
        val h = ipBox.text.toString().trim()
        val p = pinBox.text.toString().trim()
        if (h.isEmpty() || p.isEmpty()) {
            say("Enter IP and PIN")
            return
        }
        prefs.edit().putString("ip", h).apply()
        val rem = remember.isChecked
        busy(if (rem) "Waiting for the other phone to tap Allow..." else "Connecting...")
        Thread {
            try {
                if (rem) {
                    val dev = Connector.pair(applicationContext, h, p)
                    val ses = Connector.auth(applicationContext, h, dev.id)
                    runOnUiThread {
                        busy(null)
                        refresh()
                        openBrowser(ses.host, ses.token, dev.id, dev.name)
                    }
                } else {
                    runOnUiThread {
                        busy(null)
                        openBrowser(h, p, "", h)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    busy(null)
                    say(e.message ?: "Failed")
                }
            }
        }.start()
    }

    private fun connectSaved(d: Trust.Dev) {
        busy("Looking for ${d.name}...")
        Thread {
            try {
                val h = Connector.locate(d.id, d.ip)
                    ?: throw Exception("${d.name} not found. Start sharing there and use the same Wi-Fi / hotspot.")
                runOnUiThread { busy("Logging in...") }
                val ses = Connector.auth(applicationContext, h, d.id)
                runOnUiThread {
                    busy(null)
                    openBrowser(ses.host, ses.token, d.id, d.name)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    busy(null)
                    say(e.message ?: "Failed")
                }
            }
        }.start()
    }

    // ---------- list rows ----------
    private fun label(t: String) = TextView(this).also {
        it.text = t; it.textSize = 14f; it.setPadding(0, dp(6), 0, dp(2))
    }

    private fun row(text: String, vararg btns: Pair<String, () -> Unit>): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        val t = TextView(this)
        t.text = text
        t.textSize = 16f
        r.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        for ((name, fn) in btns) {
            val b = Button(this)
            b.text = name
            b.setOnClickListener { fn() }
            r.addView(b)
        }
        return r
    }

    // ---------- storage / shizuku ----------
    private fun hasStorage(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun askStorage() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            requestPermissions(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 2
            )
        }
    }

    private fun askShizuku() {
        try {
            when {
                !Shizuku.pingBinder() -> say("Start Shizuku first, then tap again")
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> say("Already enabled")
                Shizuku.shouldShowRequestPermissionRationale() -> say("Allow PhoneLink inside the Shizuku app")
                else -> Shizuku.requestPermission(10)
            }
        } catch (e: Throwable) {
            say("Install and start Shizuku first")
        }
    }

    private fun say(m: String) = Toast.makeText(this, m, Toast.LENGTH_LONG).show()

    private fun toggleServer() {
        val svc = Intent(this, FileServerService::class.java)
        if (FileServerService.running) {
            stopService(svc)
            FileServerService.running = false
        } else {
            if (!hasStorage()) {
                askStorage()
                return
            }
            val pin = (100000..999999).random().toString()
            FileServerService.pin = pin
            startForegroundService(svc.putExtra("pin", pin))
            FileServerService.running = true
        }
        refresh()
    }

    private fun ips(): List<String> {
        val out = ArrayList<String>()
        val all = NetworkInterface.getNetworkInterfaces() ?: return out
        for (ni in all) {
            if (!ni.isUp || ni.isLoopback) continue
            for (a in ni.inetAddresses) {
                if (a is Inet4Address && !a.isLoopbackAddress) out.add("${ni.name}: ${a.hostAddress}")
            }
        }
        return out
    }

    private fun refresh() {
        val me = "Phone: ${Store.deviceName()}\n"
        if (FileServerService.running) {
            toggle.text = "Stop sharing"
            info.text = me + "ON\nPIN: ${FileServerService.pin}\nAndroid/data (Shizuku): " +
                (if (ShizukuBridge.ready()) "ready" else "not ready") +
                "\nUse the wlan0 / ap0 IP:\n" + ips().joinToString("\n")
        } else {
            toggle.text = "2. Start sharing"
            info.text = me + "OFF\nStorage access: " + (if (hasStorage()) "granted" else "not granted") +
                "\nAndroid/data (Shizuku): " + if (ShizukuBridge.ready()) "ready" else "not ready"
        }

        trustBox.removeAllViews()
        val clients = Trust.clients(this)
        if (clients.isNotEmpty()) {
            trustBox.addView(label("Phones allowed here (no PIN needed):"))
            for (d in clients) {
                trustBox.addView(row(d.name, "Remove" to { Trust.removeClient(this, d.id); refresh() }))
            }
        }

        savedBox.removeAllViews()
        val saved = Trust.servers(this)
        if (saved.isEmpty()) {
            savedBox.addView(label("No saved phones yet. Connect once with IP + PIN below."))
        } else {
            savedBox.addView(label("Saved phones:"))
            for (d in saved) {
                savedBox.addView(
                    row(
                        d.name,
                        "Connect" to { connectSaved(d) },
                        "Forget" to { Trust.removeServer(this, d.id); refresh() }
                    )
                )
            }
        }
    }
}
