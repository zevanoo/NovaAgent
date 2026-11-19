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
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "sessions")
data class Session(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** Folder the agent created for this session; blank until the first write. */
    val workdir: String = "",
)

@Entity(tableName = "messages")
data class Message(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val role: String,
    val content: String,
    val createdAt: Long,
    /** JSON array of tool rounds, UI-only: rendered as collapsible rows under the user message. */
    val rounds: String = "",
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

    @Query("SELECT content FROM messages WHERE sessionId = :id AND role = 'user' ORDER BY id")
    suspend fun contents(id: Long): List<String>

    @Query("SELECT content FROM messages WHERE sessionId = :id AND role = 'assistant' ORDER BY id DESC LIMIT 1")
    suspend fun lastAssistant(id: Long): String?

    @Query("SELECT title FROM sessions WHERE id = :id")
    suspend fun title(id: Long): String?

    @Query("UPDATE sessions SET workdir = :workdir WHERE id = :id")
    suspend fun setWorkdir(id: Long, workdir: String)
}

@Database(entities = [Session::class, Message::class], version = 2, exportSchema = false)
abstract class ChatDb : RoomDatabase() {
    abstract fun chat(): ChatDao

    companion object {
        @Volatile private var instance: ChatDb? = null

        /** v1 shipped without per-session workdir and tool rounds; both are additive columns. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN workdir TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE messages ADD COLUMN rounds TEXT NOT NULL DEFAULT ''")
            }
        }

        fun of(context: Context): ChatDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, ChatDb::class.java, "novaagent.db",
            ).addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
    }
}
