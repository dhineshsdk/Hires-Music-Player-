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
import com.audiophile.engine.model.ReceiptGrade
import com.audiophile.engine.model.SignalPathState
import com.audiophile.engine.ui.components.glass
import com.audiophile.engine.ui.theme.*

private fun qualityColor(level: NodeQualityLevel): Color = when (level) {
    NodeQualityLevel.BIT_PERFECT -> SoftGreen
    NodeQualityLevel.STUDIO_ENHANCED -> EmberOrange
    NodeQualityLevel.CONVERTED -> ChampagneGold
    NodeQualityLevel.WARNING -> CrimsonAlert
}

/** The signal path as a tab: an honest receipt grade, then every stage from source to output. */
@Composable
fun PathScreen(signalPath: SignalPathState, modifier: Modifier = Modifier) {
    val p = palette
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Signal path", color = p.ink, fontFamily = DisplayFamily, fontWeight = FontWeight.Bold, fontSize = 28.sp)

        if (signalPath.nodes.isEmpty()) {
            Text("Play a track to inspect the real signal chain from decoder to output.", color = p.ink2, fontSize = 14.sp)
            return@Column
        }

        val exact = signalPath.isOverallBitPerfect
        val grade = signalPath.receiptGrade
        val gradeColor = if (grade.isBitExact) p.exact else EmberOrange

        Column(
            Modifier
                .fillMaxWidth()
                .glass(RoundedCornerShape(18.dp))
                .border(1.dp, gradeColor.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (exact) "Bit-exact to the output" else "Processed on the way to output",
                    color = gradeColor, fontWeight = FontWeight.Bold, fontSize = 15.sp
                )
                if (grade != ReceiptGrade.NONE) {
                    Text(
                        grade.displayName.uppercase(),
                        color = gradeColor,
                        fontFamily = JetMono,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 10.sp,
                        modifier = Modifier
                            .glass(RoundedCornerShape(6.dp), gradeColor.copy(alpha = 0.12f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
            Text(
                when (grade) {
                    ReceiptGrade.VERIFIED -> "Hardware receipt hashes match. Direct usbfs isochronous pump wrote exact PCM frames to DAC endpoint."
                    ReceiptGrade.OS_CONFIRMED -> "Hardware receipt hashes match. Android 14 bit-perfect mixer confirmed active on external USB DAC with zero underruns."
                    ReceiptGrade.BEST_EFFORT -> "Audio processed through active DSP stages, digital gain, or shared system mixer."
                    ReceiptGrade.NONE -> "No comparison window completed yet."
                },
                color = p.ink2, fontSize = 12.5.sp, lineHeight = 17.sp
            )

            if (signalPath.decodedHashHex != null && signalPath.outputHashHex != null) {
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glass(RoundedCornerShape(8.dp), p.glassFill.copy(alpha = 0.5f))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("DECODED HASH (FNV-1A)", color = p.ink3, fontFamily = JetMono, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                        Text("0x${signalPath.decodedHashHex}", color = p.ink, fontFamily = JetMono, fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("OUTPUT HASH", color = p.ink3, fontFamily = JetMono, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "0x${signalPath.outputHashHex}",
                            color = if (grade.isBitExact) p.exact else EmberOrange,
                            fontFamily = JetMono,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Verified frames: ${signalPath.framesCompared}", color = p.ink3, fontFamily = JetMono, fontSize = 10.sp)
                    if (signalPath.firstMismatchFrame >= 0L) {
                        Text("First mismatch @ frame ${signalPath.firstMismatchFrame}", color = CrimsonAlert, fontFamily = JetMono, fontSize = 10.sp)
                    }
                }
            }
        }

        signalPath.nodes.forEachIndexed { index, node ->
            var expanded by remember(node.title) { mutableStateOf(false) }
            val tint = qualityColor(node.qualityLevel)
            Row(
                Modifier
                    .fillMaxWidth()
                    .glass(RoundedCornerShape(14.dp))
                    .clickable { expanded = !expanded }
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(14.dp)) {
                    Box(Modifier.padding(top = 4.dp).size(10.dp).clip(CircleShape).background(if (node.isActive) tint else p.ink3))
                    if (index < signalPath.nodes.lastIndex) {
                        Box(Modifier.width(2.dp).height(if (expanded) 110.dp else 36.dp).background(p.ink3.copy(alpha = 0.3f)))
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(node.title, color = if (node.isActive) p.ink else p.ink2, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(node.subtitle, color = p.ink2, fontFamily = JetMono, fontSize = 11.5.sp)
                    Text(node.qualityLevel.displayName, color = tint, fontFamily = JetMono, fontSize = 10.5.sp)
                    if (expanded) {
                        Spacer(Modifier.height(4.dp))
                        Text(node.description, color = p.ink2, fontSize = 11.5.sp, lineHeight = 16.sp)
                        node.technicalDetails.forEach { (k, v) ->
                            Text("$k: $v", color = p.ink3, fontFamily = JetMono, fontSize = 10.5.sp)
                        }
                    }
                }
            }
        }
    }
}
