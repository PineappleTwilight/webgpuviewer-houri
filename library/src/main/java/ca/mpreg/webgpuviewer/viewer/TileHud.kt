package ca.mpreg.webgpuviewer.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ca.mpreg.webgpuviewer.renderer.TileRenderer
import kotlinx.coroutines.delay

@Composable
internal fun TileHud(state: ImageViewerState, modifier: Modifier = Modifier) {
    var residency by remember { mutableStateOf<List<TileRenderer.GridResidency>>(emptyList()) }
    var lastChange by remember { mutableStateOf<TileRenderer.GridChangeEvent?>(null) }

    LaunchedEffect(state) {
        while (true) {
            residency = state.residencySnapshot()
            lastChange = state.gridTraceSnapshot().lastOrNull()
            delay(500)
        }
    }

    DisposableEffect(state) {
        onDispose { state.tileHudBounds = null }
    }

    Column(
        modifier = modifier
            .onGloballyPositioned { state.tileHudBounds = it.boundsInParent() }
            .background(Color.Black.copy(alpha = 0.6f))
            .clickable { state.dumpGridTrace() }
            .padding(8.dp),
    ) {
        BasicText(
            text = "tile HUD - tap: dump trace",
            style = TextStyle(color = Color.White, fontSize = 10.sp, fontFamily = FontFamily.Monospace),
        )
        lastChange?.let {
            BasicText(
                text = "last: ${it.reason} p=${it.page} ${it.detail}",
                style = TextStyle(color = Color.Yellow, fontSize = 10.sp, fontFamily = FontFamily.Monospace),
            )
        }
        residency.forEach { r ->
            BasicText(
                text = "p=${r.page} t=${r.tiles} pend=${r.pending} s=${r.scale} " +
                    "stable=${r.stable} dY=${r.centerYOffset} age=${r.framesSinceDrawn} " +
                    "i=${r.instanceCount}/${r.instanceCapacity}",
                style = TextStyle(color = Color.White, fontSize = 10.sp, fontFamily = FontFamily.Monospace),
            )
        }
        BasicText(
            text = "close",
            modifier = Modifier.clickable { state.showTileHud = false },
            style = TextStyle(color = Color.Gray, fontSize = 10.sp, fontFamily = FontFamily.Monospace),
        )
    }
}
