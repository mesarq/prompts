package com.linkvault.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class OfflineStore(context: Context) : SQLiteOpenHelper(context, "linkvault-offline.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE snapshots(user_id TEXT PRIMARY KEY NOT NULL, payload TEXT NOT NULL, saved_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE pending_requests(id INTEGER PRIMARY KEY AUTOINCREMENT, user_id TEXT NOT NULL, method TEXT NOT NULL, path TEXT NOT NULL, body TEXT, created_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX pending_user_idx ON pending_requests(user_id, id)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    fun saveSnapshot(userId: String, payload: String) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO snapshots(user_id,payload,saved_at) VALUES(?,?,?)", arrayOf(userId,payload,System.currentTimeMillis()))
    }
    fun loadSnapshot(userId: String): String? {
        readableDatabase.rawQuery("SELECT payload FROM snapshots WHERE user_id=?", arrayOf(userId)).use { cursor -> return if(cursor.moveToFirst()) cursor.getString(0) else null }
    }
    fun enqueue(userId: String, method: String, path: String, body: String?) {
        writableDatabase.execSQL("INSERT INTO pending_requests(user_id,method,path,body,created_at) VALUES(?,?,?,?,?)", arrayOf(userId,method,path,body,System.currentTimeMillis()))
    }
    fun pending(userId: String): List<PendingRequest> {
        val result = mutableListOf<PendingRequest>()
        readableDatabase.rawQuery("SELECT id,user_id,method,path,body FROM pending_requests WHERE user_id=? ORDER BY id", arrayOf(userId)).use { cursor ->
            while(cursor.moveToNext()) result += PendingRequest(cursor.getLong(0),cursor.getString(1),cursor.getString(2),cursor.getString(3),cursor.getString(4))
        }
        return result
    }
    fun removePending(id: Long) { writableDatabase.delete("pending_requests", "id=?", arrayOf(id.toString())) }
    fun clearUser(userId: String) {
        writableDatabase.delete("snapshots", "user_id=?", arrayOf(userId))
        writableDatabase.delete("pending_requests", "user_id=?", arrayOf(userId))
    }
}

data class PendingRequest(val id: Long, val userId: String, val method: String, val path: String, val body: String?)