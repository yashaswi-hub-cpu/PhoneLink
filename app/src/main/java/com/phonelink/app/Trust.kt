package com.phonelink.app

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch

/** A phone asking (on the sharing phone) to be remembered. Waits for the owner to tap Allow / Deny. */
class PairReq(val id: String, val name: String) {
    val latch = CountDownLatch(1)
    @Volatile var ok = false
}

object Trust {
    val pending = CopyOnWriteArrayList<PairReq>()

    class Dev(val id: String, val name: String, val secret: ByteArray, val ip: String)

    private const val CLIENTS = "clients"   // phones allowed to connect to THIS phone
    private const val SERVERS = "servers"   // phones THIS phone has paired with

    private fun sp(ctx: Context) = ctx.getSharedPreferences("trust", Context.MODE_PRIVATE)

    private fun read(ctx: Context, key: String): JSONObject = try {
        JSONObject(sp(ctx).getString(key, null) ?: "{}")
    } catch (e: Exception) {
        JSONObject()
    }

    private fun toDev(id: String, o: JSONObject): Dev =
        Dev(id, o.optString("name", id), Crypto.unhex(o.optString("secret")), o.optString("ip"))

    private fun list(ctx: Context, key: String): List<Dev> {
        val o = read(ctx, key)
        val out = ArrayList<Dev>()
        val it = o.keys()
        while (it.hasNext()) {
            val id = it.next()
            val d = o.optJSONObject(id) ?: continue
            out.add(toDev(id, d))
        }
        return out
    }

    private fun find(ctx: Context, key: String, id: String): Dev? {
        val d = read(ctx, key).optJSONObject(id) ?: return null
        return toDev(id, d)
    }

    @Synchronized
    private fun put(ctx: Context, key: String, id: String, name: String, secret: ByteArray, ip: String) {
        val o = read(ctx, key)
        o.put(
            id,
            JSONObject().put("name", name).put("secret", Crypto.hex(secret)).put("ip", ip)
                .put("at", System.currentTimeMillis())
        )
        sp(ctx).edit().putString(key, o.toString()).apply()
    }

    @Synchronized
    private fun del(ctx: Context, key: String, id: String) {
        val o = read(ctx, key)
        o.remove(id)
        sp(ctx).edit().putString(key, o.toString()).apply()
    }

    // ---- sharing side ----
    fun clients(ctx: Context): List<Dev> = list(ctx, CLIENTS)
    fun client(ctx: Context, id: String): Dev? = find(ctx, CLIENTS, id)
    fun addClient(ctx: Context, id: String, name: String, secret: ByteArray) = put(ctx, CLIENTS, id, name, secret, "")
    fun removeClient(ctx: Context, id: String) = del(ctx, CLIENTS, id)

    // ---- connecting side ----
    fun servers(ctx: Context): List<Dev> = list(ctx, SERVERS)
    fun server(ctx: Context, id: String): Dev? = find(ctx, SERVERS, id)
    fun addServer(ctx: Context, id: String, name: String, secret: ByteArray, ip: String) =
        put(ctx, SERVERS, id, name, secret, ip)

    fun setIp(ctx: Context, id: String, ip: String) {
        val d = server(ctx, id) ?: return
        put(ctx, SERVERS, id, d.name, d.secret, ip)
    }

    fun removeServer(ctx: Context, id: String) = del(ctx, SERVERS, id)
}
