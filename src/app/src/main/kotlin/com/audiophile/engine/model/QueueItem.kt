package com.audiophile.engine.model

data class QueueItem(
    val index: Int,
    val mediaId: String,
    val title: String,
    val artist: String,
    val album: String,
    val isPlaying: Boolean
)
