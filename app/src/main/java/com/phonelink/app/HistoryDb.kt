package com.phonelink.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

class HistoryDb private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx.applicationContext, "phonelink.db", null, 3) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE history (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                direction   TEXT NOT NULL,
                host        TEXT NOT NULL,
                rel_path    TEXT NOT NULL,
                name        TEXT NOT NULL,
                size        INTEGER NOT NULL,
                bytes_done  INTEGER NOT NULL,
                status      TEXT NOT NULL,
                started_at  INTEGER NOT NULL,
                finished_at INTEGER,
                background  INTEGER NOT NULL DEFAULT 0,
                local_path  TEXT
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_history_started ON history(started_at DESC)")

        db.execSQL("""
            CREATE TABLE folder_cache (
                host     TEXT NOT NULL,
                path     TEXT NOT NULL,
                json     TEXT NOT NULL,
                cached_at INTEGER NOT NULL,
                PRIMARY KEY (host, path)
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE queue (
                id          INTEGER PRIMARY KEY AUTOINCREMENT,
                host        TEXT NOT NULL,
                rel_path    TEXT NOT NULL,
                name        TEXT NOT NULL,
                size        INTEGER NOT NULL,
                added_at    INTEGER NOT NULL
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE mirror (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                host TEXT NOT NULL,
                remote_path TEXT NOT NULL,
                local_dir TEXT NOT NULL,
                last_scan_at INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        if (oldV < 2) {
            db.execSQL("ALTER TABLE history ADD COLUMN background INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE history ADD COLUMN local_path TEXT")
        }
        if (oldV < 3) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS mirror (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    host TEXT NOT NULL,
                    remote_path TEXT NOT NULL,
                    local_dir TEXT NOT NULL,
                    last_scan_at INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())
        }
    }

    data class HistoryRow(
        val id: Long, val direction: String, val host: String,
        val relPath: String, val name: String, val size: Long,
        val bytesDone: Long, val status: String,
        val startedAt: Long, val finishedAt: Long?
    )

    fun startTransfer(
        direction: String, host: String, relPath: String,
        name: String, size: Long, background: Boolean = false, localPath: String? = null
    ): Long {
        val v = ContentValues().apply {
            put("direction", direction)
            put("host", host)
            put("rel_path", relPath)
            put("name", name)
            put("size", size)
            put("bytes_done", 0L)
            put("status", "queued")
            put("started_at", System.currentTimeMillis())
            put("background", if (background) 1 else 0)
            put("local_path", localPath)
        }
        return writableDatabase.insert("history", null, v)
    }

    fun updateProgress(id: Long, bytesDone: Long) {
        val v = ContentValues().apply {
            put("bytes_done", bytesDone)
            put("status", "running")
        }
        writableDatabase.update("history", v, "id=?", arrayOf(id.toString()))
    }

    fun finishTransfer(id: Long, status: String, bytesDone: Long) {
        val v = ContentValues().apply {
            put("bytes_done", bytesDone)
            put("status", status)
            put("finished_at", System.currentTimeMillis())
        }
        writableDatabase.update("history", v, "id=?", arrayOf(id.toString()))
    }

    fun recent(limit: Int = 200): List<HistoryRow> {
        val out = ArrayList<HistoryRow>()
        readableDatabase.rawQuery(
            "SELECT id,direction,host,rel_path,name,size,bytes_done,status,started_at,finished_at " +
                "FROM history ORDER BY started_at DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(HistoryRow(
                    c.getLong(0), c.getString(1), c.getString(2), c.getString(3),
                    c.getString(4), c.getLong(5), c.getLong(6), c.getString(7),
                    c.getLong(8), if (c.isNull(9)) null else c.getLong(9)
                ))
            }
        }
        return out
    }

    fun unfinished(): List<HistoryRow> {
        val out = ArrayList<HistoryRow>()
        readableDatabase.rawQuery(
            "SELECT id,direction,host,rel_path,name,size,bytes_done,status,started_at,finished_at " +
                "FROM history WHERE status IN ('running','queued') AND direction='down'",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(HistoryRow(
                    c.getLong(0), c.getString(1), c.getString(2), c.getString(3),
                    c.getString(4), c.getLong(5), c.getLong(6), c.getString(7),
                    c.getLong(8), if (c.isNull(9)) null else c.getLong(9)
                ))
            }
        }
        return out
    }

    fun clearHistory() = writableDatabase.delete("history", null, null)

    fun markOrphansFailed() {
        val v = ContentValues().apply {
            put("status", "failed")
            put("finished_at", System.currentTimeMillis())
        }
        writableDatabase.update("history", v, "status IN ('running','queued')", null)
    }

    fun cacheFolder(host: String, path: String, json: String) {
        val v = ContentValues().apply {
            put("host", host)
            put("path", path)
            put("json", json)
            put("cached_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            "folder_cache", null, v, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun getCachedFolder(host: String, path: String): Pair<String, Long>? {
        readableDatabase.rawQuery(
            "SELECT json, cached_at FROM folder_cache WHERE host=? AND path=?",
            arrayOf(host, path)
        ).use { c ->
            if (c.moveToFirst()) return c.getString(0) to c.getLong(1)
        }
        return null
    }

    fun clearCache() = writableDatabase.delete("folder_cache", null, null)

    data class QueueRow(
        val id: Long, val host: String, val relPath: String,
        val name: String, val size: Long, val addedAt: Long
    )

    fun enqueue(host: String, relPath: String, name: String, size: Long): Long {
        val v = ContentValues().apply {
            put("host", host)
            put("rel_path", relPath)
            put("name", name)
            put("size", size)
            put("added_at", System.currentTimeMillis())
        }
        return writableDatabase.insert("queue", null, v)
    }

    fun dequeue(id: Long) {
        writableDatabase.delete("queue", "id=?", arrayOf(id.toString()))
    }

    fun queueAll(): List<QueueRow> {
        val out = ArrayList<QueueRow>()
        readableDatabase.rawQuery(
            "SELECT id,host,rel_path,name,size,added_at FROM queue ORDER BY added_at ASC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(QueueRow(c.getLong(0), c.getString(1), c.getString(2),
                    c.getString(3), c.getLong(4), c.getLong(5)))
            }
        }
        return out
    }

    fun clearQueue() = writableDatabase.delete("queue", null, null)

    fun updateMirrorScan(host: String, remotePath: String, at: Long) {
        val v = ContentValues().apply { put("last_scan_at", at) }
        writableDatabase.update("mirror", v, "host=? AND remote_path=?", arrayOf(host, remotePath))
    }

    fun mirrors(): List<Mirror.Root> {
        val out = ArrayList<Mirror.Root>()
        readableDatabase.rawQuery(
            "SELECT host,remote_path,local_dir,last_scan_at FROM mirror ORDER BY id ASC", null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(Mirror.Root(
                    c.getString(0), c.getString(1), File(c.getString(2)), c.getLong(3)
                ))
            }
        }
        return out
    }

    fun removeMirror(host: String, remotePath: String) {
        writableDatabase.delete("mirror", "host=? AND remote_path=?", arrayOf(host, remotePath))
    }

    companion object {
        @Volatile private var inst: HistoryDb? = null
        fun get(ctx: Context): HistoryDb =
            inst ?: synchronized(this) {
                inst ?: HistoryDb(ctx).also {
                    it.markOrphansFailed()
                    inst = it
                }
            }
    }
}
