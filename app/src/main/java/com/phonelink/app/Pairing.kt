package com.phonelink.app

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object Pairing {

    private const val TOKEN_PREFS = "pair_tokens"
    private const val VAULT_PREFS = "pair_vault"
    private const val VAULT_META  = "pair_vault_meta"
    private const val STRIKE_LIMIT = 8
    private const val PBKDF2_ITER = 200_000
    private const val KEY_BITS = 256

    fun saveToken(ctx: Context, host: String, token: String, deviceName: String) {
        prefs(ctx).edit()
            .putString("t:$host", token)
            .putString("n:$host", deviceName)
            .apply()
        Vault.forgetPin(ctx, host)
    }

    fun getToken(ctx: Context, host: String): String? =
        prefs(ctx).getString("t:$host", null)

    fun deviceName(ctx: Context, host: String): String? =
        prefs(ctx).getString("n:$host", null)

    fun pairedHosts(ctx: Context): List<Pair<String, String>> =
        prefs(ctx).all.entries
            .filter { it.key.startsWith("t:") && it.value is String }
            .mapNotNull { e ->
                val host = e.key.removePrefix("t:")
                val name = prefs(ctx).getString("n:$host", host) ?: host
                host to name
            }

    fun unpair(ctx: Context, host: String) {
        prefs(ctx).edit().remove("t:$host").remove("n:$host").apply()
    }

    fun sign(token: String, deviceId: String, ts: Long, path: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(token.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val msg = "$deviceId|$ts|$path".toByteArray(Charsets.UTF_8)
        return mac.doFinal(msg).joinToString("") { "%02x".format(it) }
    }

    fun deviceIdOf(token: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(token.toByteArray(Charsets.UTF_8))
            .take(8).joinToString("") { "%02x".format(it) }
    }

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(TOKEN_PREFS, Context.MODE_PRIVATE)

    object Vault {

        @Volatile private var sessionKey: ByteArray? = null

        fun isUnlocked(): Boolean = sessionKey != null
        fun lock() { sessionKey?.fill(0); sessionKey = null }

        fun hasPin(ctx: Context, host: String): Boolean =
            try { vault(ctx).contains("p:$host") } catch (e: Exception) { false }

        fun isVaultConfigured(ctx: Context): Boolean = meta(ctx).contains("salt")

        fun setupPassword(ctx: Context, password: String) {
            val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
            meta(ctx).edit().putString("salt", salt.toHex()).putInt("strikes", 0).apply()
            sessionKey = derive(password, salt)
        }

        fun unlock(ctx: Context, password: String): Boolean {
            val salt = meta(ctx).getString("salt", null)?.hexToBytes() ?: return false
            val key = derive(password, salt)
            val probe = try { vault(ctx).getString("__probe", null) } catch (e: Exception) { null }
            if (probe == null) {
                sessionKey = key
                try { vault(ctx).edit().putString("__probe", enc(key, "ok")).apply() } catch (e: Exception) { }
                return true
            }
            val ok = try { dec(key, probe) == "ok" } catch (e: Exception) { false }
            if (!ok) {
                val n = meta(ctx).getInt("strikes", 0) + 1
                if (n >= STRIKE_LIMIT) wipe(ctx) else meta(ctx).edit().putInt("strikes", n).apply()
                return false
            }
            meta(ctx).edit().putInt("strikes", 0).apply()
            sessionKey = key
            return true
        }

        fun savePin(ctx: Context, host: String, pin: String) {
            val key = sessionKey ?: throw IllegalStateException("Vault is locked")
            vault(ctx).edit().putString("p:$host", enc(key, pin)).apply()
        }

        fun readPin(ctx: Context, host: String): String? {
            val key = sessionKey ?: return null
            val blob = vault(ctx).getString("p:$host", null) ?: return null
            return try { dec(key, blob) } catch (e: Exception) { null }
        }

        fun forgetPin(ctx: Context, host: String) {
            try { vault(ctx).edit().remove("p:$host").apply() } catch (e: Exception) { }
        }

        fun wipe(ctx: Context) {
            lock()
            try { vault(ctx).edit().clear().apply() } catch (e: Exception) { }
            meta(ctx).edit().clear().apply()
        }

        fun strikesLeft(ctx: Context): Int =
            STRIKE_LIMIT - meta(ctx).getInt("strikes", 0)

        private fun derive(password: String, salt: ByteArray): ByteArray {
            val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITER, KEY_BITS)
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).encoded
        }

        private fun enc(key: ByteArray, plain: String): String {
            val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            c.init(javax.crypto.Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"),
                javax.crypto.spec.GCMParameterSpec(128, nonce))
            val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
            return (nonce + ct).toHex()
        }

        private fun dec(key: ByteArray, blobHex: String): String {
            val all = blobHex.hexToBytes()
            val nonce = all.copyOfRange(0, 12)
            val ct = all.copyOfRange(12, all.size)
            val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            c.init(javax.crypto.Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"),
                javax.crypto.spec.GCMParameterSpec(128, nonce))
            return String(c.doFinal(ct), Charsets.UTF_8)
        }

        private fun vault(ctx: Context): SharedPreferences {
            val mk = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            return EncryptedSharedPreferences.create(
                ctx, VAULT_PREFS, mk,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }

        private fun meta(ctx: Context): SharedPreferences =
            ctx.applicationContext.getSharedPreferences(VAULT_META, Context.MODE_PRIVATE)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    private fun String.hexToBytes(): ByteArray =
        ByteArray(length / 2) { (this[it * 2].digitToInt(16) shl 4 or this[it * 2 + 1].digitToInt(16)).toByte() }

    object Server {
        private const val PREFS = "srv_pair"
        private const val SALT  = "srv_salt"

        private fun prefs(ctx: Context): SharedPreferences =
            ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        fun salt(ctx: Context): String {
            val p = prefs(ctx)
            p.getString(SALT, null)?.let { return it }
            val s = ByteArray(16).also { SecureRandom().nextBytes(it) }
                .joinToString("") { "%02x".format(it) }
            p.edit().putString(SALT, s).apply()
            return s
        }

        fun issueToken(ctx: Context, clientName: String): String {
            val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
                .joinToString("") { "%02x".format(it) }
            val hash = hashToken(ctx, raw)
            prefs(ctx).edit()
                .putString("h:$hash", clientName)
                .putLong("seen:$hash", System.currentTimeMillis())
                .apply()
            return raw
        }

        fun verify(ctx: Context, token: String): Boolean {
            val h = hashToken(ctx, token)
            return prefs(ctx).contains("h:$h")
        }

        fun verifySig(ctx: Context, token: String, deviceId: String, ts: Long, path: String, sig: String): Boolean {
            if (!verify(ctx, token)) return false
            if (kotlin.math.abs(System.currentTimeMillis() - ts) > 60_000) return false
            val expect = Pairing.sign(token, deviceId, ts, path)
            return MessageDigest.isEqual(expect.toByteArray(), sig.toByteArray())
        }

        fun list(ctx: Context): JSONArray {
            val arr = JSONArray()
            prefs(ctx).all.forEach { (k, v) ->
                if (k.startsWith("h:")) {
                    val h = k.removePrefix("h:")
                    arr.put(JSONObject()
                        .put("name", v as? String ?: "device")
                        .put("id", h.take(12))
                        .put("seen", prefs(ctx).getLong("seen:$h", 0)))
                }
            }
            return arr
        }

        fun revoke(ctx: Context, idPrefix: String) {
            val keys = prefs(ctx).all.keys.filter { it.startsWith("h:$idPrefix") }
            val e = prefs(ctx).edit()
            keys.forEach { e.remove(it); e.remove("seen:${it.removePrefix("h:")}") }
            e.apply()
        }

        fun revokeAll(ctx: Context) {
            val e = prefs(ctx).edit()
            prefs(ctx).all.keys.filter { it.startsWith("h:") || it.startsWith("seen:") }
                .forEach { e.remove(it) }
            e.apply()
        }

        private fun hashToken(ctx: Context, raw: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(salt(ctx).toByteArray())
            md.update(raw.toByteArray())
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
