package com.phonelink.app

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

object QueueWorker {

    private const val POLL_MS = 20_000L
    private val pool = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false

    fun start(ctx: Context) {
        if (running) return
        running = true
        loop(ctx.applicationContext)
    }

    fun stop() { running = false }

    private fun loop(ctx: Context) {
        main.postDelayed({
            if (!running) return@postDelayed
            pool.execute {
                try { tick(ctx) } catch (e: Exception) { }
                loop(ctx)
            }
        }, POLL_MS)
    }

    private fun tick(ctx: Context) {
        val rows = HistoryDb.get(ctx).queueAll()
        if (rows.isEmpty()) return

        for (q in rows) {
            val host = q.host
            val token = Pairing.getToken(ctx, host) ?: continue
            if (!canReach(host, token)) continue

            val svc = Intent(ctx, TransferService::class.java)
                .setAction(TransferService.ACTION_DOWNLOAD)
                .putExtra(TransferService.EXTRA_HOST, host)
                .putExtra(TransferService.EXTRA_PIN, "")
                .putExtra(TransferService.EXTRA_REL, q.relPath)
                .putExtra(TransferService.EXTRA_NAME, q.name)
                .putExtra(TransferService.EXTRA_SIZE, q.size)
            ctx.startForegroundService(svc)
            HistoryDb.get(ctx).dequeue(q.id)
        }
    }

    private fun canReach(host: String, token: String): Boolean {
        return try {
            val url = Net.url(host, "list", "", "", "", token)
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 3000
            c.readTimeout = 3000
            c.requestMethod = "GET"
            val ok = c.responseCode == 200
            c.disconnect()
            ok
        } catch (e: Exception) { false }
    }
}
