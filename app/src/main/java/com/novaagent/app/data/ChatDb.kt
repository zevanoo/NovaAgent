package com.novaagent.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

@Entity(tableName = "sessions")
data class Session(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "messages")
data class Message(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val role: String,
    val content: String,
    val createdAt: Long,
)

@Dao
interface ChatDao {
    @Query("SELECT * FROM sessions ORDER BY updatedAt DESC")
    suspend fun sessions(): List<Session>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun session(id: Long): Session?

    @Insert
    suspend fun insertSession(session: Session): Long

    @Query("UPDATE sessions SET title = :title, updatedAt = :at WHERE id = :id")
    suspend fun touchSession(id: Long, title: String, at: Long)

    @Query("SELECT * FROM messages WHERE sessionId = :id ORDER BY id")
    suspend fun messages(id: Long): List<Message>

    @Insert
    suspend fun insertMessage(message: Message): Long

    @Query("DELETE FROM messages WHERE sessionId = :id")
    suspend fun clearMessages(id: Long)

    @Query("SELECT content FROM messages WHERE sessionId = :id ORDER BY id")
    suspend fun contents(id: Long): List<String>
}

@Database(entities = [Session::class, Message::class], version = 1, exportSchema = false)
abstract class ChatDb : RoomDatabase() {
    abstract fun chat(): ChatDao

    companion object {
        @Volatile private var instance: ChatDb? = null

        fun of(context: Context): ChatDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, ChatDb::class.java, "novaagent.db",
            ).build().also { instance = it }
        }
    }
}
