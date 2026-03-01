package com.example.applora.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entité Room pour la persistance des messages.
 * deviceAddress identifie la conversation (un appareil = une conversation).
 */
@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey
    val id: String,
    val content: String,
    val isSent: Boolean,
    val timestamp: Long,
    val senderName: String? = null,
    /** Adresse Bluetooth du pair (pour filtrer par conversation). */
    val deviceAddress: String,
    val isSystemMessage: Boolean = false
)
