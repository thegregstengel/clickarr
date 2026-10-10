package net.clickarr.ui.design

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The TV mark from brand/svg/logo-mark.svg. */
@Composable
fun ClickarrMark(size: Dp, modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.ic_clickarr_mark), contentDescription = null, modifier = modifier.size(size))
}

/** The outlined wordmark ("Click" white, "arr" in the brand gradient). Height sets the scale. */
@Composable
fun ClickarrWordmark(height: Dp, modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.ic_clickarr_wordmark), contentDescription = "Clickarr", modifier = modifier.height(height))
}

/** Mark beside wordmark, as in the shell header and the overlay (design language 8). */
@Composable
fun ClickarrLogoHorizontal(markSize: Dp = 40.dp, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(markSize / 4)) {
        ClickarrMark(markSize)
        ClickarrWordmark(markSize * 0.62f)
    }
}

/** Mark above wordmark, as on the setup screen. */
@Composable
fun ClickarrLogoStacked(markSize: Dp = 160.dp, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(markSize / 8)) {
        ClickarrMark(markSize)
        ClickarrWordmark(markSize * 0.36f)
    }
}

/** The translucent pill the logo sits in over video. */
@Composable
fun ClickarrLogoPill(modifier: Modifier = Modifier) {
    ClickarrLogoHorizontal(
        markSize = 36.dp,
        modifier = modifier
            .background(ClickarrColors.BgSurface.copy(alpha = 0.7f), RoundedCornerShape(ClickarrDimens.RadiusBadge))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
