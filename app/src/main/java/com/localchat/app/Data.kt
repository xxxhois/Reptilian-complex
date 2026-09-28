package com.localchat.app

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "conversations")
data class Conversation(@PrimaryKey val id: String, val title: String, val updatedAt: Long)

@Entity(tableName = "messages", indices = [Index("conversationId")])
data class Message(
    @PrimaryKey val id: String, val conversationId: String, val role: String,
    val content: String, val status: String = "completed", val createdAt: Long = System.currentTimeMillis(),
    val attachmentName: String? = null, val attachmentUri: String? = null, val attachmentMime: String? = null
)

@Dao interface ChatDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC") fun conversations(): Flow<List<Conversation>>
    @Query("SELECT * FROM messages WHERE conversationId = :id ORDER BY createdAt ASC") fun messages(id: String): Flow<List<Message>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun saveConversation(item: Conversation)
    @Insert(onConflict = OnConflictStrategy.REPLACE) fun saveMessage(item: Message)
    @Query("UPDATE conversations SET updatedAt = :updatedAt WHERE id = :id") fun touchConversation(id: String, updatedAt: Long)
    @Query("DELETE FROM conversations WHERE id = :id") fun deleteConversation(id: String)
    @Query("DELETE FROM messages WHERE conversationId = :id") fun deleteMessages(id: String)
}

@Database(entities = [Conversation::class, Message::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() { abstract fun chatDao(): ChatDao
    companion object { fun create(context: Context) = Room.databaseBuilder(context, AppDatabase::class.java, "local-chat.db").allowMainThreadQueries().build() }
}
