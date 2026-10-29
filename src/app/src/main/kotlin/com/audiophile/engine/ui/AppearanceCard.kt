package com.audiophile.engine.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.audiophile.engine.ui.components.glass
import com.audiophile.engine.ui.theme.*

/** Look and DAP skin pickers. The choice is saved and applies at once. */
@Composable
fun AppearanceCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(
        modifier.fillMaxWidth().glass(RoundedCornerShape(22.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Appearance", color = palette.ink, fontFamily = DisplayFamily, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        Text("LOOK", color = palette.ink2, fontFamily = JetMono, fontSize = 10.sp, letterSpacing = 1.6.sp)
        Segments(AppLook.entries.map { it.title }, LookState.look.ordinal) { LookState.setLook(context, AppLook.entries[it]) }
        Text("DAP MODE SKIN", color = palette.ink2, fontFamily = JetMono, fontSize = 10.sp, letterSpacing = 1.6.sp)
        Segments(DapSkin.entries.map { it.title }, LookState.dapSkin.ordinal) { LookState.setDapSkin(context, DapSkin.entries[it]) }
    }
}

@Composable
private fun Segments(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(40.dp).background(palette.recessed, RoundedCornerShape(20.dp)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .then(if (on) Modifier.glass(RoundedCornerShape(17.dp), palette.glassFillStrong) else Modifier)
                    .clickable(role = Role.RadioButton) { onSelect(i) },
                contentAlignment = Alignment.Center
            ) {
                Text(label, color = if (on) palette.ink else palette.ink2, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}
