package com.phonelink.app

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Everything the connecting phone does before browsing: find the other phone, pair, log in. */
object Connector {
    class Hello(val id: String, val name: String)
    class Session(val host: String, val token: String)

    private fun get(host: String, ep: String, params: String, connectMs: Int, readMs: Int): String {
        val q = if (params.isEmpty()) "" else "?$params"
        val c = Lan.open(URL("http://$host:$PORT/$ep$q")) as HttpURLConnection
        c.connectTimeout = connectMs
        c.readTimeout = readMs
        c.setRequestProperty("Accept-Encoding", "identity")
        try {
            val code = c.responseCode
            if (code == 200) return c.inputStream.bufferedReader().use { it.readText() }
            val err = try {
                c.errorStream?.bufferedReader()?.use { it.readText() }
            } catch (e: Exception) {
                null
            }
            throw IOException(err ?: "HTTP $code")
        } finally {
            c.disconnect()
        }
    }

    /** Asks "who are you?" (no PIN needed). Null if nothing answers. */
    fun hello(host: String, ms: Int = 500): Hello? = try {
        val o = JSONObject(get(host, "hello", "", ms, ms * 2))
        if (o.optString("app") == "phonelink") Hello(o.getString("id"), o.optString("name", host)) else null
    } catch (e: Exception) {
        null
    }

    private fun localAddrs(): List<String> {
        val out = ArrayList<String>()
        val rx = Regex("^(wlan|ap|swlan|softap|eth|p2p|rndis|usb|bt-pan|br).*")
        try {
            val all = NetworkInterface.getNetworkInterfaces() ?: return out
            for (ni in all) {
                if (!ni.isUp || ni.isLoopback || !rx.containsMatchIn(ni.name)) continue
                for (a in ni.inetAddresses) {
                    if (a is Inet4Address && !a.isLoopbackAddress) a.hostAddress?.let { out.add(it) }
                }
            }
        } catch (e: Exception) {
        }
        return out
    }

    /** Looks through the local Wi-Fi / hotspot network for the phone with this id. */
    private fun scan(id: String): String? {
        val mine = localAddrs()
        val bases = mine.map { it.substringBeforeLast('.') }.distinct()
        val found = AtomicReference<String?>(null)
        val pool = Executors.newFixedThreadPool(48)
        try {
            for (base in bases) {
                val latch = CountDownLatch(254)
                for (i in 1..254) {
                    val ip = "$base.$i"
                    pool.execute {
                        try {
                            if (found.get() == null && ip !in mine) {
                                val h = hello(ip, 350)
                                if (h != null && h.id == id) found.set(ip)
                            }
                        } finally {
                            latch.countDown()
                        }
                    }
                }
                latch.await(20, TimeUnit.SECONDS)
                if (found.get() != null) break
            }
        } finally {
            pool.shutdownNow()
        }
        return found.get()
    }

    /** Last known IP first, then a quick scan. The IP can change; the phone's id does not. */
    fun locate(id: String, lastIp: String): String? {
        if (lastIp.isNotEmpty()) {
            val h = hello(lastIp, 900)
            if (h != null && h.id == id) return lastIp
        }
        return scan(id)
    }

    /** First time: IP + PIN, then the other phone must tap Allow. Secret is agreed with ECDH (never sent). */
    fun pair(ctx: Context, host: String, pin: String): Trust.Dev {
        val kp = Crypto.newKeyPair()
        val me = Store.deviceId(ctx)
        val q = "t=${Net.enc(pin)}&id=${Net.enc(me)}&name=${Net.enc(Store.deviceName())}" +
            "&pk=${Crypto.b64(kp.public.encoded)}"
        val o = JSONObject(get(host, "pair", q, 3000, 70000))
        val secret = Crypto.agree(kp.private, Crypto.unb64(o.getString("pk")))
        val sid = o.getString("sid")
        Trust.addServer(ctx, sid, o.optString("sname", host), secret, host)
        return Trust.server(ctx, sid) ?: throw IOException("Could not save phone")
    }

    /** Next times: prove who we are (HMAC challenge), and check the other phone is really the paired one. */
    fun auth(ctx: Context, host: String, id: String): Session {
        val dev = Trust.server(ctx, id) ?: throw IOException("Phone not saved. Connect with IP and PIN.")
        val me = Store.deviceId(ctx)
        val nonce = get(host, "challenge", "", 2000, 4000).trim()
        val sig = Crypto.hex(Crypto.hmac(dev.secret, "c|$nonce|$me"))
        val o = JSONObject(get(host, "auth", "id=${Net.enc(me)}&nonce=${Net.enc(nonce)}&sig=$sig", 2000, 5000))
        val token = o.getString("token")
        val want = Crypto.hex(Crypto.hmac(dev.secret, "s|$nonce|$token"))
        if (!Crypto.same(want, o.optString("proof"))) throw IOException("That is not the paired phone")
        Trust.setIp(ctx, id, host)
        return Session(host, token)
    }

    /** Used by the browser when the link breaks: find the phone again and log in again. */
    fun relogin(ctx: Context, id: String, lastHost: String): Session? = try {
        val h = locate(id, lastHost)
        if (h == null) null else auth(ctx, h, id)
    } catch (e: Exception) {
        null
    }
}
