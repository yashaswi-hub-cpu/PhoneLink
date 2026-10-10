package com.phonelink.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Base64
import java.net.Inet4Address
import java.net.URL
import java.net.URLConnection
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

const val PORT = 8765
const val BLOCK = 4L shl 20      // download block size
const val UBLOCK = 8L shl 20     // upload block size
const val WORKERS = 8            // parallel connections

object Net {
    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
    fun url(host: String, ep: String, path: String, pin: String, extra: String = ""): String =
        "http://$host:$PORT/$ep?path=${enc(path)}&t=${enc(pin)}$extra"
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

fun fmtSpeed(mbps: Double): String = "%.1f".format(mbps) + " MB/s"

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

/** Small crypto helpers used for phone pairing / recognition. */
object Crypto {
    private val rnd = SecureRandom()

    fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun unhex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    fun unb64(s: String): ByteArray = Base64.decode(s, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    fun rand(n: Int): String {
        val b = ByteArray(n)
        rnd.nextBytes(b)
        return hex(b)
    }

    fun hmac(key: ByteArray, msg: String): ByteArray {
        val m = Mac.getInstance("HmacSHA256")
        m.init(SecretKeySpec(key, "HmacSHA256"))
        return m.doFinal(msg.toByteArray())
    }

    fun same(a: String, b: String): Boolean = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    fun newKeyPair(): KeyPair {
        val g = KeyPairGenerator.getInstance("EC")
        g.initialize(ECGenParameterSpec("secp256r1"))
        return g.generateKeyPair()
    }

    /** ECDH: both phones get the same secret without ever sending it. */
    fun agree(priv: PrivateKey, otherPub: ByteArray): ByteArray {
        val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(otherPub))
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(priv)
        ka.doPhase(pub, true)
        return MessageDigest.getInstance("SHA-256").digest(ka.generateSecret())
    }
}

/** This phone's identity (used when pairing). */
object Store {
    fun deviceId(ctx: Context): String {
        val sp = ctx.getSharedPreferences("p", Context.MODE_PRIVATE)
        val old = sp.getString("did", null)
        if (old != null) return old
        val id = Crypto.rand(8)
        sp.edit().putString("did", id).apply()
        return id
    }

    fun deviceName(): String = Build.MODEL ?: "Android phone"
}

/**
 * Offline mode: when Wi-Fi / hotspot has no internet, Android may send app traffic over mobile data
 * and the other phone becomes unreachable. This keeps phone-to-phone traffic on the Wi-Fi network.
 */
object Lan {
    @Volatile var net: Network? = null
    private var cm: ConnectivityManager? = null
    private var started = false

    @Synchronized
    fun start(ctx: Context) {
        if (started) return
        started = true
        try {
            val m = ctx.applicationContext.getSystemService(ConnectivityManager::class.java)
            cm = m
            val req = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
            m.registerNetworkCallback(req, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    net = network
                }

                override fun onLost(network: Network) {
                    if (net == network) net = null
                }
            })
        } catch (e: Exception) {
        }
    }

    private fun sameNet(n: Network, host: String): Boolean = try {
        val lp = cm?.getLinkProperties(n)
        val pre = host.substringBeforeLast('.')
        lp != null && lp.linkAddresses.any {
            val a = it.address
            a is Inet4Address && a.hostAddress?.substringBeforeLast('.') == pre
        }
    } catch (e: Exception) {
        false
    }

    /** Opens a connection on the Wi-Fi network when the target is on it, otherwise the normal way. */
    fun open(u: URL): URLConnection {
        val n = net
        if (n != null && sameNet(n, u.host)) {
            try {
                return n.openConnection(u)
            } catch (e: Exception) {
            }
        }
        return u.openConnection()
    }

    /** For the video player (it opens its own connections). Pass null to release. */
    fun bindProcess(host: String?) {
        try {
            val n = net
            cm?.bindProcessToNetwork(if (host != null && n != null && sameNet(n, host)) n else null)
        } catch (e: Exception) {
        }
    }
}
