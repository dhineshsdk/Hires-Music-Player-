package com.audiophile.engine.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.audiophile.engine.model.NodeQualityLevel
import com.audiophile.engine.model.SignalPathState
import com.audiophile.engine.ui.theme.*

private fun qualityColor(level: NodeQualityLevel): Color = when (level) {
    NodeQualityLevel.BIT_PERFECT -> MatrixGreen
    NodeQualityLevel.STUDIO_ENHANCED -> EmberOrange
    NodeQualityLevel.CONVERTED -> ChampagneGold
    NodeQualityLevel.WARNING -> CrimsonAlert
}

/** The signal path as a tab: a verdict, then every stage from source to output. Tap a stage for details. */
@Composable
fun PathScreen(signalPath: SignalPathState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Signal path", color = TextPrimary, fontFamily = DisplayFamily, fontWeight = FontWeight.SemiBold, fontSize = 24.sp)

        if (signalPath.nodes.isEmpty()) {
            Text("Play a track to see the path its audio takes.", color = TextSecondary, fontSize = 13.sp)
            return@Column
        }

        val exact = signalPath.isOverallBitPerfect
        val verdictColor = if (exact) MatrixGreen else EmberOrange
        Column(
            Modifier
                .fillMaxWidth()
                .border(1.dp, verdictColor, RoundedCornerShape(12.dp))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                if (exact) "Bit-exact to the output" else "Processed on the way to the output",
                color = verdictColor, fontWeight = FontWeight.SemiBold, fontSize = 14.sp
            )
            Text(
                if (exact) "No stage changes the samples." else "At least one stage changes the samples; see the amber stages below.",
                color = TextPrimary, fontSize = 12.sp
            )
        }

        signalPath.nodes.forEachIndexed { index, node ->
            var expanded by remember(node.title) { mutableStateOf(false) }
            val tint = qualityColor(node.qualityLevel)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(14.dp)) {
                    Box(Modifier.padding(top = 4.dp).size(11.dp).clip(CircleShape).background(if (node.isActive) tint else DarkSurfaceBorder))
                    if (index < signalPath.nodes.lastIndex) {
                        Box(Modifier.width(2.dp).height(if (expanded) 120.dp else 42.dp).background(DarkSurfaceBorder))
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(node.title, color = if (node.isActive) TextPrimary else TextSecondary, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(node.subtitle, color = TextSecondary, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp)
                    Text(node.qualityLevel.displayName, color = tint, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp)
                    if (expanded) {
                        Text(node.description, color = TextSecondary, fontSize = 11.5.sp)
                        node.technicalDetails.forEach { (k, v) ->
                            Text("$k: $v", color = TextTertiary, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp)
                        }
                    }
                }
            }
        }
    }
}
