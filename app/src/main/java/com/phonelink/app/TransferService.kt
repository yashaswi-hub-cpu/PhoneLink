package com.phonelink.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.provider.OpenableColumns
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class TransferService : Service() {

    companion object {
        const val ACTION_DOWNLOAD = "com.phonelink.app.TRANSFER_DOWNLOAD"
        const val ACTION_UPLOAD   = "com.phonelink.app.TRANSFER_UPLOAD"
        const val ACTION_CANCEL   = "com.phonelink.app.TRANSFER_CANCEL"
        const val ACTION_STOP     = "com.phonelink.app.TRANSFER_STOP"

        const val EXTRA_HOST = "host"
        const val EXTRA_PIN  = "pin"
        const val EXTRA_REL  = "rel"
        const val EXTRA_NAME = "name"
        const val EXTRA_SIZE = "size"
        const val EXTRA_URI  = "uri"
        const val EXTRA_DIR  = "dir"
        const val EXTRA_ROW  = "rowId"

        @Volatile var running = false

        private val cancelled = ConcurrentHashMap<Long, Boolean>()
        fun cancel(rowId: Long) { cancelled[rowId] = true }
        fun isCancelled(rowId: Long): Boolean = cancelled[rowId] == true
    }

    private val pool = Executors.newFixedThreadPool(32)
    private lateinit var db: HistoryDb
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private val activeRows = ConcurrentHashMap<Long, AtomicLong>()

    private val cancelReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val rowId = intent?.getLongExtra(EXTRA_ROW, -1L) ?: -1L
            if (rowId >= 0) cancel(rowId)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        db = HistoryDb.get(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel("xfer", "Transfers", NotificationManager.IMPORTANCE_LOW)
        )
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(cancelReceiver, android.content.IntentFilter(ACTION_CANCEL),
                Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(cancelReceiver, android.content.IntentFilter(ACTION_CANCEL))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        running = true
        startForegroundCompat(buildNotification("Preparing…"))
        acquireLocks()

        when (intent?.action) {
            ACTION_DOWNLOAD -> handleDownload(intent)
            ACTION_UPLOAD   -> handleUpload(intent)
            ACTION_CANCEL   -> intent.getLongExtra(EXTRA_ROW, -1).takeIf { it >= 0 }?.let { cancel(it) }
            ACTION_STOP     -> {
                activeRows.keys.forEach { cancel(it) }
                stopSelfSafely()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseLocks()
        running = false
        try { unregisterReceiver(cancelReceiver) } catch (e: Exception) { }
        activeRows.keys.forEach { db.finishTransfer(it, "failed", activeRows[it]?.get() ?: 0) }
        activeRows.clear()
        super.onDestroy()
    }

    private fun handleDownload(intent: Intent) {
        val host = intent.getStringExtra(EXTRA_HOST) ?: return stopSelfSafely()
        val pin  = intent.getStringExtra(EXTRA_PIN) ?: ""
        val rel  = intent.getStringExtra(EXTRA_REL)  ?: return stopSelfSafely()
        val name = intent.getStringExtra(EXTRA_NAME) ?: "file"
        val size = intent.getLongExtra(EXTRA_SIZE, 0L)

        pool.execute {
            val rowId = db.startTransfer("down", host, rel, name, size, background = true)
            activeRows[rowId] = AtomicLong(0)
            try {
                runDownload(rowId, host, pin, rel, name, size)
                if (isCancelled(rowId)) {
                    db.finishTransfer(rowId, "cancelled", activeRows[rowId]?.get() ?: 0)
                    TransferBus.publish(evt(rowId, "down", name, size, "cancelled"))
                } else {
                    db.finishTransfer(rowId, "done", activeRows[rowId]?.get() ?: size)
                    TransferBus.publish(evt(rowId, "down", name, size, "done"))
                }
            } catch (e: Exception) {
                db.finishTransfer(rowId, "failed", activeRows[rowId]?.get() ?: 0)
                TransferBus.publish(evt(rowId, "down", name, size, "failed", e.message))
            } finally {
                activeRows.remove(rowId)
                cancelled.remove(rowId)
                updateNotification()
                if (activeRows.isEmpty()) stopSelfSafely()
            }
        }
    }

    private fun runDownload(rowId: Long, host: String, pin: String, rel: String, name: String, size: Long) {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        dir.mkdirs()
        var target = File(dir, name)
        if (target.exists() && size > 0 && target.length() == size) {
            activeRows[rowId]?.set(size)
            TransferBus.publish(evt(rowId, "down", name, size, "done"))
            return
        }
        if (!target.exists()) target.createNewFile()
        if (size > 0) {
            RandomAccessFile(target, "rw").use { it.setLength(size) }
        }

        val token = Pairing.getToken(this, host)
        val parts = if (size >= (8L shl 20)) 12 else if (size >= (4L shl 20)) 4 else 1
        val chunk = if (parts > 0 && size > 0) size / parts else size
        val done = activeRows[rowId]!!
        val latch = CountDownLatch(parts)
        val fail = AtomicReference<Exception?>(null)
        val lastUi = AtomicLong(0)

        for (k in 0 until parts) {
            pool.execute {
                try {
                    if (isCancelled(rowId)) return@execute
                    val s = k * chunk
                    val e = if (k == parts - 1) size - 1 else s + chunk - 1
                    fetchRange(rowId, host, pin, token, rel, s, e, target, done)
                } catch (ex: Exception) {
                    fail.compareAndSet(null, ex)
                } finally {
                    latch.countDown()
                }
            }
        }

        while (!latch.await(300, TimeUnit.MILLISECONDS)) {
            if (isCancelled(rowId)) return
            val d = done.get()
            if (System.currentTimeMillis() - lastUi.get() > 250) {
                lastUi.set(System.currentTimeMillis())
                db.updateProgress(rowId, d)
                TransferBus.publish(evt(rowId, "down", name, size, "running"))
                updateNotification()
            }
        }
        fail.get()?.let { throw it }
    }

    private fun fetchRange(
        rowId: Long, host: String, pin: String, token: String?,
        rel: String, s: Long, e: Long, f: File, done: AtomicLong
    ) {
        val c = openConn(host, "file", rel, pin, token)
        c.setRequestProperty("Range", "bytes=$s-$e")
        c.readTimeout = 60000
        if (c.responseCode != 206) throw Exception("HTTP ${c.responseCode}")

        FileChannel.open(f.toPath(), StandardOpenOption.WRITE).use { ch ->
            val buf = ByteArray(1 shl 20)
            var pos = s
            c.inputStream.use { i ->
                while (true) {
                    if (isCancelled(rowId)) throw InterruptedException("cancelled")
                    val n = i.read(buf)
                    if (n < 0) break
                    val bb = ByteBuffer.wrap(buf, 0, n)
                    var p = pos
                    while (bb.hasRemaining()) p += ch.write(bb, p)
                    pos += n
                    done.addAndGet(n.toLong())
                }
            }
            if (pos != e + 1) throw Exception("Incomplete range")
        }
    }

    private fun handleUpload(intent: Intent) {
        val host = intent.getStringExtra(EXTRA_HOST) ?: return stopSelfSafely()
        val pin  = intent.getStringExtra(EXTRA_PIN)  ?: ""
        val dir  = intent.getStringExtra(EXTRA_DIR)  ?: ""
        val uri  = intent.getStringExtra(EXTRA_URI)?.let(Uri::parse) ?: return stopSelfSafely()

        pool.execute {
            var name = "file"
            var size = -1L
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0) name = c.getString(ni)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
            if (size < 0) { stopSelfSafely(); return@execute }

            val rowId = db.startTransfer("up", host, dir, name, size, background = true)
            activeRows[rowId] = AtomicLong(0)
            try {
                val done = activeRows[rowId]!!
                val token = Pairing.getToken(this, host)
                val parts = if (size >= (8L shl 20)) 12 else if (size >= (4L shl 20)) 4 else 1
                val chunk = size / parts
                val latch = CountDownLatch(parts)
                val fail = AtomicReference<Exception?>(null)
                val lastUi = AtomicLong(0)

                for (k in 0 until parts) {
                    pool.execute {
                        try {
                            if (isCancelled(rowId)) return@execute
                            val s = k * chunk
                            val len = if (k == parts - 1) size - s else chunk
                            sendPart(rowId, host, pin, token, uri, dir, name, s, len, size, parts > 1, done)
                        } catch (ex: Exception) {
                            fail.compareAndSet(null, ex)
                        } finally { latch.countDown() }
                    }
                }
                while (!latch.await(300, TimeUnit.MILLISECONDS)) {
                    if (isCancelled(rowId)) break
                    val d = done.get()
                    if (System.currentTimeMillis() - lastUi.get() > 250) {
                        lastUi.set(System.currentTimeMillis())
                        db.updateProgress(rowId, d)
                        TransferBus.publish(evt(rowId, "up", name, size, "running"))
                        updateNotification()
                    }
                }
                fail.get()?.let { throw it }
                if (isCancelled(rowId)) {
                    db.finishTransfer(rowId, "cancelled", done.get())
                    TransferBus.publish(evt(rowId, "up", name, size, "cancelled"))
                } else {
                    db.finishTransfer(rowId, "done", done.get())
                    TransferBus.publish(evt(rowId, "up", name, size, "done"))
                }
            } catch (e: Exception) {
                db.finishTransfer(rowId, "failed", activeRows[rowId]?.get() ?: 0)
                TransferBus.publish(evt(rowId, "up", name, size, "failed", e.message))
            } finally {
                activeRows.remove(rowId)
                cancelled.remove(rowId)
                updateNotification()
                if (activeRows.isEmpty()) stopSelfSafely()
            }
        }
    }

    private fun sendPart(
        rowId: Long, host: String, pin: String, token: String?,
        u: Uri, dir: String, name: String, off: Long, len: Long, total: Long,
        ranged: Boolean, done: AtomicLong
    ) {
        val extra = "&name=${Net.enc(name)}" + if (ranged) "&offset=$off&total=$total" else ""
        val c = openConn(host, "upload", dir, pin, token, extra)
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/octet-stream")
        c.setFixedLengthStreamingMode(len)
        c.readTimeout = 180000

        var pfd: ParcelFileDescriptor? = null
        val ins: InputStream = if (ranged) {
            val d = contentResolver.openFileDescriptor(u, "r") ?: throw Exception("cannot open file")
            pfd = d
            FileInputStream(d.fileDescriptor).also { it.channel.position(off) }
        } else {
            contentResolver.openInputStream(u) ?: throw Exception("cannot open file")
        }
        try {
            c.outputStream.use { o ->
                val buf = ByteArray(1 shl 20)
                var left = len
                while (left > 0) {
                    if (isCancelled(rowId)) throw InterruptedException("cancelled")
                    val n = ins.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) throw Exception("read failed")
                    o.write(buf, 0, n)
                    left -= n
                    done.addAndGet(n.toLong())
                }
            }
        } finally {
            ins.close()
            pfd?.close()
        }
        if (c.responseCode != 200) throw Exception("HTTP ${c.responseCode}")
    }

    private fun openConn(host: String, ep: String, path: String, pin: String, token: String?, extra: String = ""): HttpURLConnection {
        val url = Net.url(host, ep, path, pin, extra, token)
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 60000
        c.setRequestProperty("Accept-Encoding", "identity")
        c.setRequestProperty("Connection", "keep-alive")
        return c
    }

    private fun evt(rowId: Long, dir: String, name: String, size: Long, status: String, msg: String? = null) =
        TransferBus.Event(
            rowId = rowId, direction = dir, name = name,
            bytesDone = activeRows[rowId]?.get() ?: 0L,
            size = size, bytesPerSec = 0L,
            status = status, message = msg
        )

    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "phonelink:xfer").also { it.acquire(60 * 60 * 1000L) }
        @Suppress("DEPRECATION")
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "phonelink:xfer").also { it.acquire() }
    }

    private fun releaseLocks() {
        wake?.let { if (it.isHeld) it.release() }
        wifi?.let { if (it.isHeld) it.release() }
    }

    private fun stopSelfSafely() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val b = Notification.Builder(this, "xfer")
            .setContentTitle("PhoneLink transfer")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(pi)
            .setOngoing(true)

        if (activeRows.size == 1) {
            val rowId = activeRows.keys.first()
            val cancelPi = PendingIntent.getBroadcast(
                this, rowId.toInt(),
                Intent(ACTION_CANCEL).setPackage(packageName).putExtra(EXTRA_ROW, rowId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            b.addAction(Notification.Action.Builder(null, "Cancel", cancelPi).build())
        } else if (activeRows.size > 1) {
            val cancelAllPi = PendingIntent.getBroadcast(
                this, 0,
                Intent(ACTION_STOP).setPackage(packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            b.addAction(Notification.Action.Builder(null, "Stop all", cancelAllPi).build())
        }
        return b.build()
    }

    private fun startForegroundCompat(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(2, n)
        }
    }

    private fun updateNotification() {
        val n = activeRows.size
        val total = activeRows.values.sumOf { it.get() }
        val text = if (n == 0) "Finishing…" else "$n active · ${fmtSize(total)}"
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(2, buildNotification(text))
    }
}
