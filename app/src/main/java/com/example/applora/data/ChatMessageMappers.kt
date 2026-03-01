package com.example.applora.data

import com.example.applora.ChatMessage

fun ChatMessageEntity.toChatMessage(): ChatMessage = ChatMessage(
    id = id,
    content = content,
    isSent = isSent,
    timestamp = timestamp,
    senderName = senderName,
    isSystemMessage = isSystemMessage
)

fun ChatMessage.toEntity(deviceAddress: String): ChatMessageEntity = ChatMessageEntity(
    id = id,
    content = content,
    isSent = isSent,
    timestamp = timestamp,
    senderName = senderName,
    deviceAddress = deviceAddress,
    isSystemMessage = isSystemMessage
)
