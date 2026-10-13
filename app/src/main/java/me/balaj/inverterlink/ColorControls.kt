package me.balaj.inverterlink

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.util.Locale

fun hexColor(hex: String): Color = runCatching {
    Color(0xFF000000 or hex.trim().removePrefix("#").toLong(16))
}
    .getOrDefault(Color.Blue)

private fun hsvColor(hue: Float, saturation: Float, value: Float): String =
    "%06X"
        .format(
            Locale.ROOT,
            android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value)) and 0xFFFFFF,
        )

@Composable
fun ColorControls(draft: String, onChange: (String) -> Unit) {
    fun decoded() =
        FloatArray(3).also { android.graphics.Color.colorToHSV(hexColor(draft).toArgb(), it) }
    var hue by rememberSaveable { mutableFloatStateOf(decoded()[0]) }
    var saturation by rememberSaveable { mutableFloatStateOf(decoded()[1]) }
    var brightness by rememberSaveable { mutableFloatStateOf(decoded()[2]) }
    LaunchedEffect(draft) {
        val color = decoded()
        if (color[1] > 0 && color[2] > 0) hue = color[0]
        saturation = color[1]
        brightness = color[2]
    }
    val hsv = floatArrayOf(hue, saturation, brightness)
    val currentHue by rememberUpdatedState(hue)
    fun choose(h: Float, s: Float, v: Float) {
        hue = h
        saturation = s
        brightness = v
        onChange(hsvColor(h, s, v))
    }
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Color",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Box(Modifier.size(32.dp).clip(CircleShape).background(hexColor(draft)))
        }
        Box(Modifier.fillMaxWidth().height(160.dp).testTag("colorSaturationValue")) {
            Canvas(
                Modifier.fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures { p ->
                            choose(
                                currentHue,
                                (p.x / size.width).coerceIn(0f, 1f),
                                (1f - p.y / size.height).coerceIn(0f, 1f),
                            )
                        }
                    }
                    .pointerInput(Unit) {
                        detectDragGestures { change, _ ->
                            change.consume()
                            val p = change.position
                            choose(
                                currentHue,
                                (p.x / size.width).coerceIn(0f, 1f),
                                (1f - p.y / size.height).coerceIn(0f, 1f),
                            )
                        }
                    }
                    .semantics { contentDescription = "Color saturation and brightness" }
            ) {
                drawRect(
                    Brush.horizontalGradient(
                        listOf(
                            Color.White,
                            Color(android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f))),
                        )
                    )
                )
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                val point = Offset(hsv[1] * size.width, (1f - hsv[2]) * size.height)
                drawCircle(
                    Color.Black,
                    9.dp.toPx(),
                    point,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()),
                )
                drawCircle(
                    Color.White,
                    8.dp.toPx(),
                    point,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()),
                )
            }
        }
        Text("Hue", style = MaterialTheme.typography.labelMedium)
        Box(contentAlignment = Alignment.Center) {
            Box(
                Modifier.fillMaxWidth()
                    .height(12.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.horizontalGradient(
                            (0..6).map {
                                Color(
                                    android.graphics.Color.HSVToColor(
                                        floatArrayOf(it * 60f, 1f, 1f)
                                    )
                                )
                            }
                        )
                    )
            )
            Slider(
                hsv[0],
                { choose(it, hsv[1], hsv[2]) },
                valueRange = 0f..360f,
                colors =
                    SliderDefaults.colors(
                        activeTrackColor = Color.Transparent,
                        inactiveTrackColor = Color.Transparent,
                    ),
                modifier = Modifier.semantics { contentDescription = "Hue" }.testTag("colorHue"),
            )
        }
        // Native sliders make the two-dimensional picker fully operable with TalkBack.
        Text("Saturation", style = MaterialTheme.typography.labelMedium)
        Slider(
            hsv[1],
            { choose(hsv[0], it, hsv[2]) },
            modifier = Modifier.semantics { contentDescription = "Saturation" },
        )
        Text("Brightness", style = MaterialTheme.typography.labelMedium)
        Slider(
            hsv[2],
            { choose(hsv[0], hsv[1], it) },
            modifier = Modifier.semantics { contentDescription = "Brightness" },
        )
        val presets =
            listOf(
                "Blue" to "0000FF",
                "Cyan" to "00FFFF",
                "Green" to "00FF00",
                "Yellow" to "FFFF00",
                "Orange" to "FF8000",
                "Red" to "FF0000",
                "Purple" to "9400FF",
                "White" to "FFFFFF",
            )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            presets.forEach { (name, hex) ->
                Box(
                    Modifier.size(48.dp)
                        .clip(CircleShape)
                        .background(hexColor(hex))
                        .clickable { onChange(hex) }
                        .semantics {
                            contentDescription = name
                            selected = draft.removePrefix("#").equals(hex, true)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (draft.removePrefix("#").equals(hex, true))
                        Symbol(
                            R.drawable.ic_done,
                            null,
                            Modifier.size(24.dp),
                            tint =
                                if (hexColor(hex).luminance() > .45f) Color.Black else Color.White,
                        )
                }
            }
        }
        OutlinedTextField(
            draft,
            { onChange(it.uppercase(Locale.ROOT).removePrefix("#")) },
            label = { Text("Hex color") },
            prefix = { Text("#") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            modifier = Modifier.fillMaxWidth().testTag("settingValueInput"),
        )
    }
}
