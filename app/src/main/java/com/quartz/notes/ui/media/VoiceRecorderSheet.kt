package com.quartz.notes.ui.media

import android.media.MediaRecorder
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.quartz.notes.ui.components.QuartzSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.quartz.notes.LocalAppContainer
import com.quartz.notes.ui.theme.LocalQuartzColors
import kotlinx.coroutines.delay
import kotlin.math.sqrt

private val RecordRed = Color(0xFFE53935)

/**
 * Records a voice note. Recording starts as soon as the sheet opens. Stop/Save or swiping the
 * sheet away keeps it; "Discard" deletes it. Call only after microphone permission is granted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceRecorderSheet(onSaved: (path: String, durationMs: Long) -> Unit, onDismiss: () -> Unit) {
    val container = LocalAppContainer.current
    val context = LocalContext.current
    val path = remember { container.media.newRecordingPath() }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var failed by remember { mutableStateOf(false) }
    var elapsed by remember { mutableLongStateOf(0L) }
    val levels = remember { mutableStateListOf<Float>() }
    var finished by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val r = MediaRecorder(context)
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(44_100)
            r.setAudioEncodingBitRate(128_000)
            r.setOutputFile(container.media.file(path).absolutePath)
            r.prepare()
            r.start()
            recorder = r
        } catch (t: Throwable) {
            r.release()
            failed = true
        }
        onDispose {
            recorder?.let { runCatching { it.stop() }; it.release() }
            recorder = null
            if (!finished) container.media.delete(path)
        }
    }

    LaunchedEffect(recorder) {
        val r = recorder ?: return@LaunchedEffect
        val start = System.currentTimeMillis()
        while (true) {
            elapsed = System.currentTimeMillis() - start
            val amp = runCatching { r.maxAmplitude }.getOrDefault(0)
            levels.add(sqrt(amp / 32767f).coerceIn(0.04f, 1f))
            if (levels.size > 48) levels.removeAt(0)
            delay(90)
        }
    }

    fun save() {
        val r = recorder ?: return
        val ok = runCatching { r.stop() }.isSuccess
        r.release()
        recorder = null
        if (ok) {
            finished = true
            onSaved(path, elapsed)
        } else {
            container.media.delete(path)
            onDismiss()
        }
    }

    QuartzSheet(
        // Swiping the sheet away keeps the recording; only "Discard" throws it out.
        onDismissRequest = { if (recorder != null && elapsed >= 800) save() else onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 28.dp).navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(if (failed) "Couldn't start recording" else "Recording", fontSize = 20.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Text(formatDuration(elapsed), fontSize = 40.sp, fontWeight = FontWeight.Light, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(16.dp))
            val barColor = MaterialTheme.colorScheme.primary
            Canvas(Modifier.fillMaxWidth().height(64.dp)) {
                val count = 48
                val gap = 3.dp.toPx()
                val bw = (size.width - gap * (count - 1)) / count
                val offset = count - levels.size
                levels.forEachIndexed { i, level ->
                    val h = size.height * level
                    drawRoundRect(
                        color = barColor,
                        topLeft = Offset((i + offset) * (bw + gap), (size.height - h) / 2),
                        size = Size(bw, h),
                        cornerRadius = CornerRadius(bw / 2),
                    )
                }
            }
            Spacer(Modifier.height(22.dp))
            val pulse = rememberInfiniteTransition(label = "pulse").animateFloat(
                initialValue = 1f, targetValue = 1.12f,
                animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse",
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                TextButton(onClick = onDismiss) { Text("Discard") }
                Surface(
                    onClick = ::save,
                    enabled = recorder != null,
                    shape = CircleShape,
                    color = RecordRed,
                    modifier = Modifier.size(76.dp).scale(if (recorder != null) pulse.value else 1f),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (recorder != null) Icons.Rounded.Stop else Icons.Rounded.Mic,
                            contentDescription = "Stop and save",
                            tint = Color.White,
                            modifier = Modifier.size(38.dp),
                        )
                    }
                }
                TextButton(onClick = ::save, enabled = recorder != null) { Text("Save") }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "Tap stop to save the voice note",
                fontSize = 13.sp,
                color = LocalQuartzColors.current.textSecondary,
            )
            Box(Modifier.height(4.dp).background(Color.Transparent, RoundedCornerShape(2.dp)))
        }
    }
}

fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}
