package com.taehagen.spotifygood.model

import kotlinx.serialization.Serializable

@Serializable
data class SpotifyNotification(
    val id: String,
    val storageId: String = "",
    val createdTimestamp: String = "",
    val title: String = "",
    val action: NotificationAction? = null,
    val entityImage: NotificationImage? = null,
    val isNew: Boolean = false,
)

@Serializable
data class NotificationAction(val uri: String? = null, val type: String? = null)

@Serializable
data class NotificationImage(val imageUrl: String? = null, val entityUrl: String? = null)
