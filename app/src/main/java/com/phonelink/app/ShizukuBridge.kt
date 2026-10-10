package com.phonelink.app

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelFileDescriptor
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Runs inside the Shizuku process (shell uid). Can read /Android/data and /Android/obb. */
class UserService() : IUserService.Stub() {
    constructor(context: Context) : this()

    private val allowed = Regex("^/storage/[^/]+(/\\d+)?/Android/(data|obb)(/.*)?$")

    private fun check(p: String): File {
        val n = java.nio.file.Paths.get(p).normalize().toString()
        if (!allowed.matches(n)) throw SecurityException("path not allowed")
        return File(n)
    }

    private inline fun <T> guard(b: () -> T): T = try {
        b()
    } catch (e: SecurityException) {
        throw e
    } catch (e: Exception) {
        throw IllegalStateException(e.message ?: "error")
    }

    override fun destroy() { System.exit(0) }
    override fun exit() { System.exit(0) }

    override fun list(path: String): String = guard {
        val arr = JSONArray()
        for (f in check(path).listFiles() ?: emptyArray()) {
            val d = f.isDirectory
            arr.put(JSONObject().put("n", f.name).put("d", d).put("s", if (d) 0L else f.length()))
        }
        arr.toString()
    }

    override fun stat(path: String): String = guard {
        val f = check(path)
        if (f.isDirectory) "d" else if (f.isFile) "f${f.length()}" else ""
    }

    override fun openRead(path: String): ParcelFileDescriptor = guard {
        ParcelFileDescriptor.open(check(path), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun openWrite(path: String): ParcelFileDescriptor = guard {
        ParcelFileDescriptor.open(
            check(path),
            ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
        )
    }
}

/** App side: connects to the UserService through Shizuku. */
object ShizukuBridge {
    @Volatile private var svc: IUserService? = null
    @Volatile private var latch = CountDownLatch(1)

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            svc = IUserService.Stub.asInterface(binder)
            latch.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            svc = null
        }
    }

    private val args = Shizuku.UserServiceArgs(ComponentName("com.phonelink.app", UserService::class.java.name))
        .daemon(false).processNameSuffix("fs").debuggable(false).version(1)

    fun ready(): Boolean = try {
        Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        false
    }

    private fun get(): IUserService {
        svc?.let { if (it.asBinder().pingBinder()) return it }
        synchronized(this) {
            svc?.let { if (it.asBinder().pingBinder()) return it }
            latch = CountDownLatch(1)
            Shizuku.bindUserService(args, conn)
            if (!latch.await(8, TimeUnit.SECONDS)) throw IllegalStateException("Shizuku service did not start")
            return svc ?: throw IllegalStateException("Shizuku service unavailable")
        }
    }

    fun list(path: String): String = get().list(path)
    fun stat(path: String): String = get().stat(path)
    fun openRead(path: String): ParcelFileDescriptor = get().openRead(path)
    fun openWrite(path: String): ParcelFileDescriptor = get().openWrite(path)

    fun release() {
        try { Shizuku.unbindUserService(args, conn, true) } catch (e: Throwable) { }
        svc = null
    }
}
