package com.example.myapplication

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import com.example.myapplication.ui.theme.positive
import com.example.myapplication.ui.theme.axisZ
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@Composable
internal fun OrientationView(estimate: OrientationEstimate?, zeroReference: MutableState<OrientationQuaternion?>) {
    val colors = MaterialTheme.colorScheme
    val minimumReadoutWidth = (80 * LocalDensity.current.fontScale.coerceAtLeast(1f)).dp
    var reference by zeroReference
    val rotation = estimate?.rotation?.let { q -> reference?.let(q::relativeTo) ?: q }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Device orientation", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            OutlinedButton(enabled = rotation != null, onClick = {
                reference = if (reference == null) estimate?.rotation else null
            }) { Text(if (reference == null) "Set zero" else "Clear zero") }
        }
        if (rotation == null) {
            Text("Waiting for a valid orientation sample", color = colors.onSurfaceVariant, fontSize = 14.sp)
        } else {
            val angles = rotation.eulerDegrees()
            Canvas(Modifier.fillMaxWidth().height(180.dp).semantics {
                contentDescription = "Device orientation preview with sensor X, Y and Z axes"
            }.clip(MaterialTheme.shapes.medium)
                .background(colors.surfaceContainerHigh)) {
                val scale = size.minDimension * .22f
                fun project(x: Double, y: Double, z: Double): Pair<Offset, Double> {
                    val p = rotation.rotate(x, y, z)
                    return Offset(size.width/2 + ((.866*p.first - .5*p.second)*scale).toFloat(),
                        size.height/2 + ((.25*p.first + .433*p.second - .866*p.third)*scale).toFloat()) to
                        (.433*p.first + .75*p.second + .5*p.third)
                }
                val corners = listOf(
                    Triple(-1.2,-.7,-.12), Triple(1.2,-.7,-.12), Triple(1.2,.7,-.12), Triple(-1.2,.7,-.12),
                    Triple(-1.2,-.7,.12), Triple(1.2,-.7,.12), Triple(1.2,.7,.12), Triple(-1.2,.7,.12)
                ).map { project(it.first, it.second, it.third) }
                val faces = listOf(listOf(0,1,2,3), listOf(4,5,6,7), listOf(0,1,5,4),
                    listOf(1,2,6,5), listOf(2,3,7,6), listOf(3,0,4,7))
                faces.sortedBy { face -> face.sumOf { corners[it].second } }.forEach { face ->
                    val path = Path().apply {
                        face.forEachIndexed { index, corner ->
                            val p = corners[corner].first
                            if (index == 0) moveTo(p.x,p.y) else lineTo(p.x,p.y)
                        }
                        close()
                    }
                    drawPath(path, if (face == faces[1]) colors.primaryContainer else colors.outlineVariant)
                    drawPath(path, if (face == faces[1]) colors.primary else colors.onSurfaceVariant,
                        style = Stroke(1.dp.toPx()))
                }
                val axes = listOf(Triple(1.8,0.0,0.0), Triple(0.0,1.8,0.0), Triple(0.0,0.0,1.8))
                val axisColors = listOf(colors.error, colors.positive, colors.axisZ)
                val origin = project(0.0,0.0,0.0).first
                axes.forEachIndexed { index, axis ->
                    val end = project(axis.first, axis.second, axis.third).first
                    drawLine(axisColors[index], origin, end, 2.dp.toPx())
                    drawCircle(axisColors[index], 3.dp.toPx(), end)
                    drawContext.canvas.nativeCanvas.drawText(listOf("X","Y","Z")[index],
                        end.x + 6.dp.toPx(), end.y, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                            color = axisColors[index].toArgb(); textSize = 13.sp.toPx()
                        })
                }
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Roll" to angles.first, "Pitch" to angles.second, "Yaw (relative)" to angles.third).forEachIndexed { index, (label, value) ->
                    Column(Modifier.widthIn(min = minimumReadoutWidth).weight(1f)) {
                        Text(label, color = colors.onSurfaceVariant, fontSize = 12.sp)
                        Text(String.format(Locale.US, "%+.1f\u00b0", value),
                            color = listOf(colors.error, colors.positive, colors.axisZ)[index],
                            fontFamily = FontFamily.Monospace, fontSize = 20.sp)
                    }
                }
            }
        }
        Text(if (estimate?.stationary == true) "Still: drift correction active. Yaw is relative."
            else "Hold still briefly to reduce drift. Set zero to mark your starting direction.",
            color = colors.onSurfaceVariant, fontSize = 12.sp)
    }
}
