package lk.peliyagoda.marketpro.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.Locale

/**
 * UI state is only a view of the checked, downloaded APK.
 * The installer is unlocked solely by UpdateStatus.Ready AND the
 * MarketProAutoUpdater.Ready instance held by the caller.
 */
internal sealed interface UpdateStatus {
    data object Checking : UpdateStatus
    data class Downloading(
        val version: String,
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : UpdateStatus
    data object Verifying : UpdateStatus
    data class Ready(val version: String) : UpdateStatus
    data object UpToDate : UpdateStatus
    data class Failed(val reason: String) : UpdateStatus
}

@Composable
internal fun MarketUpdateProgressDialog(
    status: UpdateStatus,
    onDismiss: () -> Unit,
    onInstall: () -> Unit,
    onRetry: () -> Unit
) {
    val active = status == UpdateStatus.Checking ||
        status is UpdateStatus.Downloading || status == UpdateStatus.Verifying

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color.White,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
            tonalElevation = 4.dp
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = when (status) {
                        UpdateStatus.Checking -> "Checking For Updates"
                        is UpdateStatus.Downloading -> "Downloading Update"
                        UpdateStatus.Verifying -> "Verifying APK Security"
                        is UpdateStatus.Ready -> "Download Completed"
                        UpdateStatus.UpToDate -> "Market Pro Is Up To Date"
                        is UpdateStatus.Failed -> "Update Download Failed"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = ProColors.navy,
                    textAlign = TextAlign.Center
                )

                when (status) {
                    UpdateStatus.Checking,
                    UpdateStatus.Verifying -> Box(
                        modifier = Modifier.size(148.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(126.dp),
                            color = ProColors.teal,
                            strokeWidth = 10.dp,
                            trackColor = Color(0xFFE4F2F4)
                        )
                        Text(
                            text = if (status == UpdateStatus.Verifying) "SHA-256" else "Checking",
                            fontWeight = FontWeight.Bold,
                            color = ProColors.navy
                        )
                    }

                    is UpdateStatus.Downloading -> {
                        val downloaded = status.downloadedBytes.coerceAtLeast(0L)
                        val total = status.totalBytes.coerceAtLeast(1L)
                        val fraction = (downloaded.toFloat() / total).coerceIn(0f, 1f)
                        val animated by animateFloatAsState(
                            targetValue = fraction,
                            animationSpec = tween(durationMillis = 220),
                            label = "Real APK download progress"
                        )
                        val percent = (fraction * 100).toInt().coerceIn(0, 100)
                        Box(
                            modifier = Modifier.size(150.dp).semantics {
                                contentDescription = "APK downloaded $percent percent"
                            },
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                progress = { animated },
                                modifier = Modifier.size(138.dp),
                                color = ProColors.teal,
                                trackColor = Color(0xFFE2F1F3),
                                strokeWidth = 12.dp,
                                strokeCap = StrokeCap.Round
                            )
                            Text(
                                "$percent%",
                                style = MaterialTheme.typography.headlineMedium,
                                color = ProColors.navy,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            status.version + "  •  " +
                                String.format(
                                    Locale.US, "%.1f / %.1f MB",
                                    downloaded / 1048576.0, total / 1048576.0
                                ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = ProColors.muted
                        )
                    }

                    is UpdateStatus.Ready, UpdateStatus.UpToDate ->
                        AnimatedSuccessCheck()

                    is UpdateStatus.Failed -> Box(
                        modifier = Modifier.size(140.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "!",
                            style = MaterialTheme.typography.displayLarge,
                            color = ProColors.red,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Text(
                    text = when (status) {
                        UpdateStatus.Checking ->
                            "Finding the latest signed Market Pro version."
                        is UpdateStatus.Downloading ->
                            "Please keep your internet connection active. " +
                            "Progress reflects bytes received from the server."
                        UpdateStatus.Verifying ->
                            "Checking the APK checksum, package name, " +
                            "signing certificate and version."
                        is UpdateStatus.Ready ->
                            "Version ${status.version} is verified and ready. " +
                            "Your existing records and Cloud login will be retained."
                        UpdateStatus.UpToDate ->
                            "You already have the latest available update."
                        is UpdateStatus.Failed ->
                            status.reason.take(180)
                    },
                    color = ProColors.muted,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(Modifier.height(2.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(if (active) "Hide" else "Later")
                    }
                    when (status) {
                        is UpdateStatus.Ready -> {
                            Button(onClick = onInstall) {
                                Text("Install Update", fontWeight = FontWeight.Bold)
                            }
                        }
                        is UpdateStatus.Failed -> {
                            Button(onClick = onRetry) {
                                Text("Retry", fontWeight = FontWeight.Bold)
                            }
                        }
                        else -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun AnimatedSuccessCheck() {
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { started = true }
    val progress by animateFloatAsState(
        targetValue = if (started) 1f else 0f,
        animationSpec = tween(durationMillis = 720),
        label = "Download completed checkmark animation"
    )
    val successGreen = Color(0xFF28C66E)

    Canvas(
        modifier = Modifier.size(145.dp).semantics {
            contentDescription = "Download completed successfully"
        }
    ) {
        val w = size.width
        val h = size.height
        val lineWidth = 9.dp.toPx()

        drawArc(
            color = successGreen,
            startAngle = -90f,
            sweepAngle = 360f * progress,
            useCenter = false,
            style = Stroke(width = lineWidth, cap = StrokeCap.Round)
        )

        val a = Offset(w * 0.27f, h * 0.52f)
        val b = Offset(w * 0.44f, h * 0.68f)
        val c = Offset(w * 0.75f, h * 0.33f)

        fun between(from: Offset, to: Offset, fraction: Float): Offset = Offset(
            from.x + (to.x - from.x) * fraction,
            from.y + (to.y - from.y) * fraction
        )

        val tick = ((progress - 0.35f) / 0.65f).coerceIn(0f, 1f)
        if (tick > 0f) {
            drawLine(
                color = successGreen,
                start = a,
                end = between(a, b, (tick * 2f).coerceIn(0f, 1f)),
                strokeWidth = lineWidth,
                cap = StrokeCap.Round
            )
        }
        if (tick > 0.5f) {
            drawLine(
                color = successGreen,
                start = b,
                end = between(b, c, ((tick - 0.5f) * 2f).coerceIn(0f, 1f)),
                strokeWidth = lineWidth,
                cap = StrokeCap.Round
            )
        }
    }
}
