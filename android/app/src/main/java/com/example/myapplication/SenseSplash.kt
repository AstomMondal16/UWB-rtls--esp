package com.example.myapplication

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

@Composable
internal fun SenseSplash() {
    val logo = ImageBitmap.imageResource(R.drawable.sense_logo)
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(1200, easing = LinearEasing)) }
    val background = MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxSize().background(background).padding(24.dp),
        contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Surface(color = background, shape = MaterialTheme.shapes.large) {
                Canvas(Modifier.widthIn(max = 312.dp).fillMaxWidth().padding(16.dp)
                    .aspectRatio(logo.width.toFloat()/logo.height).semantics { contentDescription = "Sense logo with a progressing wave" }) {
                    val destination = IntSize(size.width.toInt(), size.height.toInt())
                    drawImage(logo, dstSize = destination)
                    // Reveal only the wave's original pixels; the crest and lettering stay intact.
                    val left = size.width*174f/304f
                    val right = size.width*239f/304f
                    clipRect(left = left, right = right) {
                        drawRect(background)
                        drawImage(logo, dstSize = destination, alpha = .16f)
                        clipRect(right = left+(right-left)*progress.value) {
                            drawImage(logo, dstSize = destination)
                        }
                    }
                }
            }
            Text("UWB localization", color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.offset(y = (-8).dp))
        }
    }
}
