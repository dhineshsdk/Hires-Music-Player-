package com.audiophile.engine.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.audiophile.engine.model.QueueItem
import com.audiophile.engine.ui.components.glass
import com.audiophile.engine.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(
    queue: List<QueueItem>,
    onPlayIndex: (Int) -> Unit,
    onRemoveIndex: (Int) -> Unit,
    onClearQueue: () -> Unit,
    onDismiss: () -> Unit
) {
    val p = palette

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = p.ground,
        dragHandle = {
            Box(
                Modifier
                    .padding(vertical = 10.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(p.ink3)
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.75f)
                .padding(horizontal = 20.dp, vertical = 6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "Up Next",
                        color = p.ink,
                        fontFamily = DisplayFamily,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "${queue.size} ${if (queue.size == 1) "track" else "tracks"} in queue",
                        color = p.ink2,
                        fontSize = 12.sp
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (queue.isNotEmpty()) {
                        TextButton(
                            onClick = onClearQueue,
                            colors = ButtonDefaults.textButtonColors(contentColor = p.ink2)
                        ) {
                            Text("Clear", fontSize = 13.sp)
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close", tint = p.ink2)
                    }
                }
            }

            if (queue.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(bottom = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Queue is empty.\nPick files or choose songs from Library.",
                        color = p.ink2,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(queue, key = { it.index }) { item ->
                        val isPlaying = item.isPlaying
                        val cardBorder = if (isPlaying) p.accent else Color.Transparent

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .glass(RoundedCornerShape(12.dp), if (isPlaying) p.accent.copy(alpha = 0.08f) else p.glassFill)
                                .border(1.dp, cardBorder, RoundedCornerShape(12.dp))
                                .clickable { onPlayIndex(item.index) }
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(if (isPlaying) p.accent else p.glassLine),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isPlaying) {
                                    Icon(
                                        Icons.Rounded.PlayArrow,
                                        contentDescription = "Playing",
                                        tint = p.onAccent,
                                        modifier = Modifier.size(18.dp)
                                    )
                                } else {
                                    Text(
                                        "${item.index + 1}",
                                        color = p.ink2,
                                        fontFamily = JetMono,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        item.title,
                                        color = if (isPlaying) p.accent else p.ink,
                                        fontSize = 14.sp,
                                        fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (isPlaying) {
                                        Text(
                                            "PLAYING",
                                            color = p.accent,
                                            fontFamily = JetMono,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 9.sp,
                                            modifier = Modifier
                                                .glass(RoundedCornerShape(4.dp), p.accent.copy(alpha = 0.15f))
                                                .padding(horizontal = 5.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Text(
                                    item.artist.ifBlank { "Unknown artist" },
                                    color = p.ink2,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            IconButton(
                                onClick = { onRemoveIndex(item.index) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.DeleteOutline,
                                    contentDescription = "Remove from queue",
                                    tint = p.ink3,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
