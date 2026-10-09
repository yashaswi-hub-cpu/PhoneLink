package com.phonelink.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : Activity() {
    private lateinit var info: TextView
    private lateinit var toggle: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "PhoneLink"
        val prefs = getSharedPreferences("p", MODE_PRIVATE)
        val pad = dp(16)

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(pad, pad, pad, pad)

        fun head(t: String) = TextView(this).also {
            it.text = t; it.textSize = 20f; it.setPadding(0, pad, 0, dp(6))
        }

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

        val tls = CheckBox(this)
        tls.text = "Use HTTPS (not implemented — keep OFF)"
        tls.isChecked = FileServerService.useTls
        tls.setOnCheckedChangeListener { _, checked -> FileServerService.useTls = checked }

        col.addView(perm); col.addView(shz); col.addView(toggle); col.addView(info); col.addView(tls)

        col.addView(head("History"))
        val hist = Button(this)
        hist.text = "Show transfer history"
        hist.setOnClickListener { showHistory() }
        val clr = Button(this)
        clr.text = "Clear offline folder cache"
        clr.setOnClickListener {
            HistoryDb.get(this).clearCache()
            say("Folder cache cleared")
        }
        val showPin = Button(this)
        showPin.text = "Show saved PIN for last IP"
        showPin.setOnClickListener { showSavedPin() }
        col.addView(hist); col.addView(clr); col.addView(showPin)

        col.addView(head("Active transfers"))
        val activeTv = TextView(this).apply { textSize = 15f }
        col.addView(activeTv)
        TransferBus.subscribe { _ ->
            val list = TransferBus.active
            activeTv.text = if (list.isEmpty()) "(none)"
            else list.joinToString("\n") { e ->
                val pct = if (e.size > 0) e.bytesDone * 100 / e.size else 0
                val dir = if (e.direction == "down") "↓" else "↑"
                "$dir ${e.name}  $pct%  ${fmtSize(e.bytesDone)}/${fmtSize(e.size)}"
            }
        }

        col.addView(head("Other phone: connect"))
        val ip = EditText(this)
        ip.hint = "Other phone IP (e.g. 192.168.1.5)"
        ip.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        ip.setText(prefs.getString("ip", ""))

        val pinBox = EditText(this)
        pinBox.hint = "PIN shown on other phone"
        pinBox.inputType = InputType.TYPE_CLASS_NUMBER
        pinBox.setText(prefs.getString("pin", ""))

        val savePin = CheckBox(this)
        savePin.text = "Remember PIN in encrypted vault"
        savePin.isChecked = true

        val go = Button(this)
        go.text = "Connect"
        go.setOnClickListener {
            val h = ip.text.toString().trim()
            val p = pinBox.text.toString().trim()
            if (h.isEmpty()) { say("Enter IP"); return@setOnClickListener }

            val token = Pairing.getToken(this, h)
            if (token != null) {
                prefs.edit().putString("ip", h).apply()
                startActivity(Intent(this, BrowserActivity::class.java).putExtra("host", h))
                return@setOnClickListener
            }
            if (p.isEmpty()) { say("Enter PIN (first time)"); return@setOnClickListener }

            if (savePin.isChecked) {
                if (!Pairing.Vault.isVaultConfigured(this)) {
                    promptNewVaultPassword { ok ->
                        if (ok) {
                            Pairing.Vault.savePin(this, h, p)
                            prefs.edit().putString("ip", h).putString("pin", p).apply()
                            startActivity(Intent(this, BrowserActivity::class.java)
                                .putExtra("host", h).putExtra("pin", p))
                        }
                    }
                } else {
                    ensureVaultUnlocked {
                        Pairing.Vault.savePin(this, h, p)
                        prefs.edit().putString("ip", h).putString("pin", p).apply()
                        startActivity(Intent(this, BrowserActivity::class.java)
                            .putExtra("host", h).putExtra("pin", p))
                    }
                }
            } else {
                prefs.edit().putString("ip", h).putString("pin", p).apply()
                startActivity(Intent(this, BrowserActivity::class.java)
                    .putExtra("host", h).putExtra("pin", p))
            }
        }
        col.addView(ip); col.addView(pinBox); col.addView(savePin); col.addView(go)

        col.addView(head("Paired devices"))
        val pairedBox = LinearLayout(this)
        pairedBox.orientation = LinearLayout.VERTICAL
        col.addView(pairedBox)
        refreshPairedDevices(pairedBox)

        col.addView(head("Offline queue"))
        val queueTv = TextView(this).apply { textSize = 15f }
        val refreshQueue = Runnable {
            val rows = HistoryDb.get(this).queueAll()
            queueTv.text = if (rows.isEmpty()) "(empty)"
            else rows.joinToString("\n") { "• ${it.name}  ${fmtSize(it.size)}" }
        }
        col.addView(queueTv)
        val qClear = Button(this)
        qClear.text = "Clear queue"
        qClear.setOnClickListener {
            HistoryDb.get(this).clearQueue(); refreshQueue.run()
        }
        col.addView(qClear)
        refreshQueue.run()

        col.addView(head("Mirrors"))
        val mirrorTv = TextView(this).apply { textSize = 15f }
        val refreshMirrors = Runnable {
            val list = HistoryDb.get(this).mirrors()
            mirrorTv.text = if (list.isEmpty()) "(none)"
            else list.joinToString("\n") { m ->
                val whenTxt = if (m.lastScanAt == 0L) "never"
                else android.text.format.DateFormat.format("MM-dd HH:mm", m.lastScanAt).toString()
                "• ${m.host}:${m.remotePath} → ${m.localDir.name} ($whenTxt)"
            }
        }
        col.addView(mirrorTv)
        val mScan = Button(this)
        mScan.text = "Scan all mirrors now"
        mScan.setOnClickListener {
            Thread {
                for (m in HistoryDb.get(this).mirrors()) {
                    Mirror.scan(this, m.host, m.remotePath, m.localDir)
                }
                runOnUiThread { refreshMirrors.run(); say("Mirror scan done") }
            }.start()
        }
        col.addView(mScan)
        refreshMirrors.run()

        val sv = ScrollView(this)
        sv.addView(col)
        setContentView(sv)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)

        val unfinished = HistoryDb.get(this).unfinished()
        if (unfinished.isNotEmpty()) {
            android.app.AlertDialog.Builder(this)
                .setTitle("Resume unfinished downloads?")
                .setMessage("${unfinished.size} transfer(s) were interrupted. Resume now?")
                .setPositiveButton("Resume") { _, _ ->
                    for (row in unfinished) {
                        startForegroundService(
                            Intent(this, TransferService::class.java)
                                .setAction(TransferService.ACTION_DOWNLOAD)
                                .putExtra(TransferService.EXTRA_HOST, row.host)
                                .putExtra(TransferService.EXTRA_PIN, "")
                                .putExtra(TransferService.EXTRA_REL, row.relPath)
                                .putExtra(TransferService.EXTRA_NAME, row.name)
                                .putExtra(TransferService.EXTRA_SIZE, row.size)
                        )
                    }
                }
                .setNegativeButton("Ignore", null)
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        QueueWorker.start(this)
    }

    override fun onDestroy() {
        QueueWorker.stop()
        super.onDestroy()
    }

    private fun hasStorage(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun askStorage() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            requestPermissions(arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE), 2)
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
            if (!hasStorage()) { askStorage(); return }
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
        if (FileServerService.running) {
            toggle.text = "Stop sharing"
            info.text = "ON\nPIN: ${FileServerService.pin}\nAndroid/data (Shizuku): " +
                (if (ShizukuBridge.ready()) "ready" else "not ready") +
                "\nUse the wlan0 / ap0 IP:\n" + ips().joinToString("\n")
        } else {
            toggle.text = "2. Start sharing"
            info.text = "OFF\nStorage access: " + (if (hasStorage()) "granted" else "not granted") +
                "\nAndroid/data (Shizuku): " + if (ShizukuBridge.ready()) "ready" else "not ready"
        }
    }

    private fun refreshPairedDevices(box: LinearLayout) {
        box.removeAllViews()
        val list = Pairing.pairedHosts(this)
        if (list.isEmpty()) {
            box.addView(TextView(this).apply { text = "(none yet — pair a phone once)" })
            return
        }
        for ((host, name) in list) {
            val b = Button(this)
            b.text = "$name\n$host"
            b.setOnClickListener {
                startActivity(Intent(this, BrowserActivity::class.java).putExtra("host", host))
            }
            b.setOnLongClickListener {
                android.app.AlertDialog.Builder(this)
                    .setTitle("Unpair $name?")
                    .setPositiveButton("Unpair") { _, _ ->
                        Pairing.unpair(this, host)
                        refreshPairedDevices(box)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
                true
            }
            box.addView(b)
        }
    }

    private fun showHistory() {
        val db = HistoryDb.get(this)
        val rows = db.recent()
        if (rows.isEmpty()) { say("No transfers yet"); return }

        val lines = rows.map { r ->
            val dir = if (r.direction == "down") "↓" else "↑"
            val pct = if (r.size > 0) (r.bytesDone * 100 / r.size) else 0
            val whenTxt = android.text.format.DateFormat.format("MM-dd HH:mm", r.startedAt)
            "$dir  ${r.name}\n     ${fmtSize(r.bytesDone)} / ${fmtSize(r.size)}  ($pct%)  ${r.status}  · $whenTxt"
        }

        val lv = ListView(this)
        lv.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_list_item_1, lines)
        android.app.AlertDialog.Builder(this)
            .setTitle("History (${rows.size})")
            .setView(lv)
            .setPositiveButton("Close", null)
            .setNeutralButton("Clear") { _, _ -> db.clearHistory(); say("History cleared") }
            .show()
    }

    private fun promptNewVaultPassword(onDone: (Boolean) -> Unit) {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val p1 = EditText(this).apply {
            hint = "New vault password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
        }
        val p2 = EditText(this).apply {
            hint = "Repeat password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
        }
        box.addView(p1); box.addView(p2)
        android.app.AlertDialog.Builder(this)
            .setTitle("Set vault password")
            .setMessage("If you forget it, saved PINs are unrecoverable. 8 wrong attempts wipe the vault.")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val a = p1.text.toString(); val b = p2.text.toString()
                if (a.length < 6 || a != b) { say("Passwords must match and be ≥ 6 chars"); onDone(false); return@setPositiveButton }
                Pairing.Vault.setupPassword(this, a)
                onDone(true)
            }
            .setNegativeButton("Cancel") { _, _ -> onDone(false) }
            .show()
    }

    private fun ensureVaultUnlocked(onOk: () -> Unit) {
        if (Pairing.Vault.isUnlocked()) { onOk(); return }
        val e = EditText(this).apply {
            hint = "Vault password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            transformationMethod = PasswordTransformationMethod.getInstance()
        }
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Unlock vault")
            .setView(e)
            .setPositiveButton("Unlock", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (Pairing.Vault.unlock(this, e.text.toString())) { dialog.dismiss(); onOk() }
                else {
                    val left = Pairing.Vault.strikesLeft(this)
                    if (left <= 0) { say("Too many wrong attempts — vault wiped"); dialog.dismiss() }
                    else { say("Wrong. $left attempts left"); e.setText("") }
                }
            }
        }
        dialog.show()
    }

    private fun showSavedPin() {
        val h = getSharedPreferences("p", MODE_PRIVATE).getString("ip", "") ?: ""
        if (h.isEmpty()) { say("No IP saved yet"); return }
        if (!Pairing.Vault.hasPin(this, h)) { say("No PIN saved for $h"); return }
        ensureVaultUnlocked {
            val pin = Pairing.Vault.readPin(this, h)
            if (pin == null) { say("PIN missing"); return@ensureVaultUnlocked }
            val box = LinearLayout(this)
            box.orientation = LinearLayout.VERTICAL
            val tv = TextView(this).apply { text = "Host: $h\nPIN: $pin"; textSize = 18f }
            box.addView(tv)
            android.app.AlertDialog.Builder(this)
                .setTitle("Saved PIN")
                .setView(box)
                .setPositiveButton("OK", null)
                .show()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                tv.text = "PIN hidden"
            }, 30_000)
        }
    }
}
