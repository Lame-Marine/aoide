package com.kafkasl.phonewhisper

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** On-device dictation history. Stored in the app's private database; never leaves the phone. */
class HistoryStore(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "history.db", null, 1) {

    data class Entry(val id: Long, val ts: Long, val text: String, val model: String, val audioMs: Int, val procMs: Int)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE history(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, " +
                "text TEXT NOT NULL, model TEXT, audio_ms INTEGER, proc_ms INTEGER)"
        )
        db.execSQL("CREATE INDEX history_ts ON history(ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    @Synchronized
    fun add(text: String, model: String, audioMs: Int, procMs: Int) {
        val v = ContentValues().apply {
            put("ts", System.currentTimeMillis())
            put("text", text)
            put("model", model)
            put("audio_ms", audioMs)
            put("proc_ms", procMs)
        }
        writableDatabase.insert("history", null, v)
    }

    @Synchronized
    fun list(limit: Int = MAX_ITEMS): List<Entry> {
        val out = ArrayList<Entry>()
        readableDatabase.rawQuery(
            "SELECT id, ts, text, model, audio_ms, proc_ms FROM history ORDER BY ts DESC, id DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(Entry(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3) ?: "", c.getInt(4), c.getInt(5)))
            }
        }
        return out
    }

    @Synchronized
    fun count(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM history", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    @Synchronized fun delete(id: Long) { writableDatabase.delete("history", "id=?", arrayOf(id.toString())) }

    @Synchronized fun clear() { writableDatabase.delete("history", null, null) }

    /** Drop entries older than [retentionDays] (0 = keep forever) and cap the total at [MAX_ITEMS]. */
    @Synchronized
    fun prune(retentionDays: Int) {
        val db = writableDatabase
        if (retentionDays > 0) {
            val cutoff = System.currentTimeMillis() - retentionDays * 24L * 3600L * 1000L
            db.delete("history", "ts < ?", arrayOf(cutoff.toString()))
        }
        db.execSQL(
            "DELETE FROM history WHERE id NOT IN (SELECT id FROM history ORDER BY ts DESC, id DESC LIMIT $MAX_ITEMS)"
        )
    }

    companion object {
        const val MAX_ITEMS = 500
        const val DEFAULT_RETENTION_DAYS = 30
    }
}
