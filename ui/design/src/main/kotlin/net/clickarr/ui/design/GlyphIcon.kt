package net.clickarr.ui.design

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor

/** A channel glyph by Lucide name, stroked in the current content color. Unknown names draw nothing. */
@Composable
fun GlyphIcon(name: String, size: Dp, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    val glyph = ClickarrGlyphs.byName(name) ?: return
    Icon(painterResource(glyph.res), contentDescription = glyph.label, modifier = modifier.size(size), tint = tint)
}
