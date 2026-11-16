package com.audiophile.engine.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.audiophile.engine.ui.components.glass
import com.audiophile.engine.ui.theme.*

data class OpenSourceLicense(
    val name: String,
    val licenseType: String,
    val copyright: String,
    val description: String,
    val complianceNote: String
)

private val kLicenses = listOf(
    OpenSourceLicense(
        name = "BitChord UI port",
        licenseType = "GPL v3",
        copyright = "BitChord contributors; upstream source attribution retained",
        description = "Floating navigation, mini-player gestures, mesh backdrop, transport layout and player glyphs adapted for our native session.",
        complianceNote = "Full license is bundled at assets/licenses/BitChord-GPL-3.0.txt. Source hashes and adaptations are recorded in docs/porting/bitchord-sources.json."
    ),
    OpenSourceLicense(
        name = "Haze 0.7.3",
        licenseType = "Apache License 2.0",
        copyright = "Copyright 2023 Christopher Banes and the Haze project contributors",
        description = "Backdrop blur for the floating navigation and mini-player; compatible with the current Compose baseline.",
        complianceNote = "Apache-2.0; upstream https://github.com/chrisbanes/haze."
    ),
    OpenSourceLicense(
        name = "libFLAC 1.4.3",
        licenseType = "BSD 3-Clause",
        copyright = "Copyright © 2000-2009 Josh Coalson, 2011-2023 Xiph.Org Foundation",
        description = "High-resolution Free Lossless Audio Codec reference C library for bit-exact decoding directly to the audio ring buffer.",
        complianceNote = "Static linking permitted under BSD-3-Clause terms."
    ),
    OpenSourceLicense(
        name = "libsoxr 0.1.3",
        licenseType = "LGPL v2.1 (PFFFT: BSD-like)",
        copyright = "Copyright © 2007-2018 Rob Sykes, PFFFT © 2013 Julien Pommier",
        description = "High quality 1D sample-rate conversion library with SIMD PFFFT Fourier transform engine for locked UAC1 DAC sample rate matching.",
        complianceNote = "Linked dynamically as libsoxr.so shared library in compliance with LGPL v2.1 Section 6."
    ),
    OpenSourceLicense(
        name = "Oboe 1.9.0",
        licenseType = "Apache License 2.0",
        copyright = "Copyright © 2016-2024 Google LLC",
        description = "Low-latency C++ audio library wrapping Android AAudio and OpenSL ES with automatic stream recovery and exclusive routing.",
        complianceNote = "Licensed under Apache 2.0."
    ),
    OpenSourceLicense(
        name = "FFmpeg (libavformat, libavcodec, libswresample, libavutil)",
        licenseType = "LGPL v2.1+",
        copyright = "Copyright © 2000-2024 the FFmpeg developers",
        description = "Multi-format container demuxing and decoding engine for ALAC, APE, WavPack, AAC, and MP3 audio.",
        complianceNote = "Linked dynamically as shared .so libraries in compliance with LGPL v2.1 Section 6. Built with GPL features disabled."
    ),
    OpenSourceLicense(
        name = "Jetpack Compose & AndroidX",
        licenseType = "Apache License 2.0",
        copyright = "Copyright © 2018-2024 The Android Open Source Project",
        description = "Modern declarative UI toolkit and Android Jetpack libraries powering the Liquid Glass interface.",
        complianceNote = "Licensed under Apache 2.0."
    ),
    OpenSourceLicense(
        name = "Kotlin Standard Library & Coroutines",
        licenseType = "Apache License 2.0",
        copyright = "Copyright © 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors",
        description = "Kotlin language runtime and asynchronous coroutine flows for telemetry and hardware state management.",
        complianceNote = "Licensed under Apache 2.0."
    )
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesSheet(
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
                .fillMaxHeight(0.8f)
                .padding(horizontal = 20.dp, vertical = 6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "Open-Source Licenses",
                        color = p.ink,
                        fontFamily = DisplayFamily,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Third-party software & LGPL compliance notices",
                        color = p.ink2,
                        fontSize = 12.sp
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close", tint = p.ink2)
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(kLicenses) { item ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .glass(RoundedCornerShape(12.dp), p.glassFill)
                            .border(1.dp, p.glassLine, RoundedCornerShape(12.dp))
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(item.name, color = p.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Text(
                                item.licenseType,
                                color = p.accent,
                                fontFamily = JetMono,
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .glass(RoundedCornerShape(4.dp), p.accent.copy(alpha = 0.12f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Text(item.copyright, color = p.ink2, fontFamily = JetMono, fontSize = 10.5.sp)
                        Spacer(Modifier.height(2.dp))
                        Text(item.description, color = p.ink, fontSize = 12.sp, lineHeight = 16.sp)
                        Text(item.complianceNote, color = p.ink3, fontFamily = JetMono, fontSize = 10.5.sp, lineHeight = 14.sp)
                    }
                }
            }
        }
    }
}
