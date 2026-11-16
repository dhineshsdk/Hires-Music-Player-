"""Import the selected BitChord UI sources, adapting platform/service seams only.

Run from repository root. Source SHA256s and transformations are recorded in
docs/porting/bitchord-sources.json. No donor application/service code is executed.
"""
from pathlib import Path
import hashlib
import json
import re
import shutil

DONOR = Path("D:/MusicPlayer-Projects/BitChord")
ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "src/app/src/main/kotlin/com/audiophile/engine/ui/bitchord"
OUT.mkdir(parents=True, exist_ok=True)
records = []


def read(relative):
    path = DONOR / relative
    raw = path.read_bytes()
    records.append({"source": relative, "sha256": hashlib.sha256(raw).hexdigest()})
    return raw.decode("utf-8-sig")


def save(name, text):
    notice = "// Ported from BitChord (GPL-3.0); see assets/licenses/BitChord-GPL-3.0.txt\n// Modifications: native-player adapters and compatibility with this app's Compose baseline.\n"
    normalized = "\n".join(line.rstrip() for line in text.splitlines()).rstrip() + "\n"
    (OUT / name).write_text(notice + normalized, encoding="utf-8")


app = "app/src/main/java/com/music/bitchord/ui/components/"
shared = "sharedUi/src/jvmSharedMain/kotlin/com/music/bitchord/ui/"
# These screen structures are adapted in CollectionBrowser.kt. Their remote
# shelf/repository models are intentionally replaced by local collection inputs.
read(shared + "screens/HomeScreen.kt")
read(shared + "screens/LibraryScreen.kt")
nav = read(app + "FloatingBottomBar.kt")
nav = nav.replace("package com.music.bitchord.ui.components", "package com.audiophile.engine.ui.bitchord")
nav = nav.replace("@OptIn(ExperimentalHazeMaterialsApi::class)", "")
nav = "\n".join(line for line in nav.splitlines() if not any(x in line for x in [
    "import com.music.", "import dev.chrisbanes", "import androidx.lifecycle", ]))
nav = nav.replace("import androidx.compose.ui.Modifier", "import androidx.compose.ui.Modifier\nimport androidx.compose.ui.graphics.ImageBitmap\nimport androidx.compose.ui.platform.LocalHapticFeedback\nimport androidx.compose.ui.hapticfeedback.HapticFeedbackType\nimport androidx.compose.ui.semantics.Role\nimport androidx.compose.ui.semantics.semantics\nimport androidx.compose.ui.semantics.selected")
nav = nav.replace("    hazeState: HazeState,", "    art: ImageBitmap?,")
nav = nav.replace("val reduceDynamicBlur by AppSettings.reduceDynamicBlur.collectAsStateWithLifecycle()", "")
nav = nav.replace("val useGlass = LocalLiquidGlassEnabled.current && isGlassSupported()", "val useGlass = false // Preserve theme-selected tint in this compatibility port.")
nav = nav.replace("val reduceAnimation by AppSettings.reduceAnimation.collectAsStateWithLifecycle()", "val reduceAnimation = LocalPortMotionReduced.current")
nav = nav.replace("val haptics = rememberHaptics()", "val haptics = LocalHapticFeedback.current")
nav = nav.replace("haptics.play(Haptic.Tick)", "haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)")
nav = nav.replace("haptics.play(Haptic.Select)", "haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)")
start = nav.index("            .then(\n                if (reduceDynamicBlur)")
end = nav.index("            .border(GLASS_EDGE_WIDTH", start)
nav = nav[:start] + "            .portGlass(pillShape, art)\n" + nav[end:]
nav = nav.replace(".clickable(\n                interactionSource", ".semantics { this.selected = selected }\n            .clickable(\n                role = Role.Tab,\n                interactionSource")
save("FloatingBottomBar.kt", nav + "\n")

# Preserve the donor's axis arbitration, release threshold and rubber-band
# curve, independent of its video/party/remote-artwork dependencies.
mini = read(app + "MiniPlayer.kt")
start = mini.index("/** Distance that makes a horizontal drag")
end = mini.index("/**\n * The mini player's cover as one end", start)
imports = mini[:mini.index("/**\n * The transport buttons")]
imports = "\n".join(line for line in imports.splitlines() if not any(x in line for x in [
    "import com.music.", "import dev.chrisbanes", "import org.jetbrains.compose", "import androidx.lifecycle", "import coil3."]))
imports = imports.replace("package com.music.bitchord.ui.components", "package com.audiophile.engine.ui.bitchord")
imports = imports.replace("import androidx.compose.runtime.Composable", "import androidx.compose.runtime.Composable\nimport androidx.compose.ui.graphics.ImageBitmap\nimport androidx.compose.foundation.Image\nimport androidx.compose.material.icons.rounded.SkipNext\nimport androidx.compose.ui.text.style.TextOverflow\nimport com.audiophile.engine.model.TrackMetadata")
gesture = mini[start:end]
save("MiniPlayer.kt", imports + "\n" + gesture + '''
/** The donor's 56dp mini-player layout with native metadata/artwork inputs. */
@Composable
fun PortMiniPlayer(
    track: TrackMetadata, art: ImageBitmap?, loading: Boolean,
    onExpand: () -> Unit, onPlayPause: () -> Unit,
    onNext: () -> Unit, onPrevious: () -> Unit,
    canNext: Boolean, canPrevious: Boolean,
    pull: MiniPlayerPull?, modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(percent = 50)
    Row(
        modifier.padding(horizontal = 19.dp)
            .miniPlayerGestures(pull, { if (canNext) onNext() }, { if (canPrevious) onPrevious() })
            .clip(shape).portGlass(shape, art)
            .border(0.5.dp, Color.White.copy(alpha = 0.10f), shape)
            .clickable(onClickLabel = "Open Now Playing", onClick = onExpand)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverArt(art, Modifier.size(40.dp), corner = 8.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(track.artist, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (loading) Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Color.White)
        } else IconButton(onClick = onPlayPause, modifier = Modifier.size(40.dp)) {
            Icon(if (track.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                if (track.isPlaying) "Pause" else "Play", modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onNext, enabled = canNext, modifier = Modifier.size(40.dp)) {
            PlayerGlyph("next", "Next track", Modifier.size(25.dp))
        }
    }
}
''')

mesh = read(shared + "player/MeshGradient.kt")
start = mesh.index("private val FallbackColors")
end = mesh.index("/**\n * How long each palette", start)
body = mesh[start:end]
body = body.replace("val reduceAnimation by PlayerSettings.reduceAnimation.collectAsStateWithLifecycle()", "val reduceAnimation = LocalPortMotionReduced.current")
helpers = mesh[mesh.index("private fun Color.tuned()"):]
save("MeshGradient.kt", '''package com.audiophile.engine.ui.bitchord
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import kotlinx.coroutines.isActive
import kotlin.math.*
private const val FADE_CURVE_STOPS = 24
private const val DRIFT_RADIANS = (PI * 0.45f).toFloat()
private fun smootherStep(t: Float) = t*t*t*(t*(t*6f-15f)+10f)
''' + body + helpers)

# Reuse the donor's actual transport layout and vector glyphs.
controls = read(shared + "player/PlayerControls.kt")
start = controls.index("@Composable\ninternal fun TransportRow(")
end = controls.index("/** The volume bar", start)
transport = controls[start:end]
transport = transport.replace("Res.drawable.ic_player_", "R.drawable.port_player_")
transport = transport.replace("stringResource(Res.string.widget_previous)", '"Previous track"')
transport = transport.replace("stringResource(Res.string.widget_next)", '"Next track"')
transport = transport.replace("stringResource(if (isPlaying) Res.string.pause else Res.string.play)", 'if (isPlaying) "Pause" else "Play"')
transport = re.sub(r"\s*haptic = .*?,\n", "\n", transport)
save("TransportRow.kt", '''package com.audiophile.engine.ui.bitchord
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.graphicsLayer
import com.audiophile.engine.R
private val PLAYER_SKIP_ICON_SIZE = 52.dp
private val PLAYER_SKIP_TOUCH_SIZE = 72.dp
private const val PLAYER_SKIP_HEIGHT_SCALE = 0.85f
''' + transport)

res = ROOT / "src/app/src/main/res/drawable"
res.mkdir(exist_ok=True)
for name in ["ic_player_next.xml", "ic_player_previous.xml", "ic_player_play.xml", "ic_player_pause.xml"]:
    relative = "sharedUi/src/commonMain/composeResources/drawable/" + name
    content = read(relative)
    content = content.replace('?attr/colorControlNormal', '@android:color/white')
    (res / name.replace("ic_player_", "port_player_")).write_text(content, encoding="utf-8")

slider = read(shared + "player/ThinSlider.kt")
slider = slider.replace("package com.music.bitchord.ui.player", "package com.audiophile.engine.ui.bitchord")
slider = slider.replace("import com.music.bitchord.data.settings.MixBlend", "")
slider = slider.replace("import androidx.compose.runtime.mutableFloatStateOf", "import androidx.compose.runtime.mutableFloatStateOf\nimport androidx.compose.runtime.mutableLongStateOf")
slider = slider.replace("mutableStateOf(System.nanoTime())", "mutableLongStateOf(System.nanoTime())")
slider = slider.replace("    mixPulse: MixPulse? = null,", "")
slider = slider.replace("mixing && mixPulse?.active != true", "mixing")
slider = slider.replace("mixPulse?.cover ?: 0f", "0f")
slider = slider.replace("mixPulse?.alpha(activeColor.alpha) ?: activeColor.alpha", "activeColor.alpha")
# Keep loading sheen and the actual capsule/gesture code; no simulated beat pulse.
start = slider.index("/**\n * One beat clock")
end = slider.index("/** Length of one morph", start)
slider = slider[:start] + slider[end:]
# Session position updates must not cancel a finger already on the scrubber.
slider = slider.replace("    var dragging by remember", "    val currentChange by rememberUpdatedState(onValueChange)\n    val currentFinished by rememberUpdatedState(onValueChangeFinished)\n    var dragging by remember")
slider = slider.replace("                    onValueChange(", "                    currentChange(")
slider = slider.replace("                            onValueChange(", "                            currentChange(")
slider = slider.replace("                    onValueChangeFinished?.invoke()", "                    currentFinished?.invoke()")
slider = slider.replace("import androidx.compose.ui.Modifier", "import androidx.compose.ui.Modifier\nimport androidx.compose.foundation.progressSemantics\nimport androidx.compose.ui.semantics.semantics\nimport androidx.compose.ui.semantics.setProgress")
slider = slider.replace("            .fillMaxWidth()\n            // Generous", "            .fillMaxWidth()\n            .progressSemantics(value.coerceIn(0f, 1f))\n            .semantics { setProgress { currentChange(it.coerceIn(0f, 1f)); currentFinished?.invoke(); true } }\n            // Generous")
save("ThinSlider.kt", slider)
assets = ROOT / "src/app/src/main/assets/licenses"
assets.mkdir(parents=True, exist_ok=True)
shutil.copy2(DONOR / "LICENSE", assets / "BitChord-GPL-3.0.txt")
(ROOT / "docs/porting/bitchord-sources.json").write_text(json.dumps({
    "source_root": str(DONOR), "files": records,
    "adaptations": ["Package renamed; host settings/haptics replaced by local adapters",
                     "ExoPlayer, remote artwork, account and party dependencies omitted",
                     "Glass uses Haze backdrop frost compatible with Compose 1.6; lens refraction deferred",
                     "Transport input is native-session callbacks, never a second player"]
}, indent=2) + "\n", encoding="utf-8")
print(f"Ported {len(records)} source/resource files; donor untouched")
