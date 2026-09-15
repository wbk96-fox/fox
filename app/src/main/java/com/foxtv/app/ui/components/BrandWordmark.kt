package com.foxtv.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.foxtv.app.ui.theme.FoxTvTheme
import com.foxtv.app.ui.theme.brandWordmarkResource

@Composable
fun BrandWordmark(
    modifier: Modifier = Modifier,
    contentDescription: String? = "FOX.TV",
    contentScale: ContentScale = ContentScale.Fit,
    alpha: Float = 1f
) {
    val wordmarkResource = FoxTvTheme.currentTheme.brandWordmarkResource

    Image(
        painter = painterResource(id = wordmarkResource),
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        alpha = alpha
    )
}
