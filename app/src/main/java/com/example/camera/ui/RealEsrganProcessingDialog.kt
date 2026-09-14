package com.example.camera.ui

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.example.camera.esrgan.RealEsrganState
import com.example.camera.model.CapturedMedia
import com.example.camera.ui.components.FrostedGlassBox

@Composable
fun RealEsrganProcessingDialog(
    media: CapturedMedia,
    state: RealEsrganState,
    onStartProcessing: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val mode = media.aiResolutionMode ?: com.example.camera.model.PhotoMegapixelMode.M50

    // Auto-trigger processing when dialog opens if not already running or finished
    LaunchedEffect(media.pendingRawFilePath) {
        if (!state.isProcessing && state.finalUri == null && media.isPendingAiProcessing) {
            onStartProcessing()
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(6000, easing = LinearEasing)
        ),
        label = "rotate"
    )

    val currentUri = state.finalUri ?: if (!state.isProcessing && !media.isPendingAiProcessing) media.uri else null

    Dialog(
        onDismissRequest = {
            if (!state.isProcessing) onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color(0xFF0D0E12))
                .testTag("real_esrgan_dialog")
        ) {
            if (currentUri != null) {
                // Display the final AI-upscaled image with full original framing
                AsyncImage(
                    model = currentUri,
                    contentDescription = "Real-ESRGAN ${mode.label} Final Photo",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // Processing UI: Do not show the original image as final result!
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // Neural Super-Resolution Glowing Ring
                    Box(
                        modifier = Modifier
                            .size(140.dp)
                            .scale(pulseScale),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .rotate(rotationAngle)
                                .border(
                                    3.dp,
                                    Brush.sweepGradient(
                                        listOf(
                                            Color(0xFF00E5FF),
                                            Color(0xFF7C4DFF),
                                            Color(0xFFFF4081),
                                            Color(0xFF00E5FF)
                                        )
                                    ),
                                    CircleShape
                                )
                        )
                        Box(
                            modifier = Modifier
                                .size(110.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.radialGradient(
                                        listOf(
                                            Color(0xFF7C4DFF).copy(alpha = 0.4f),
                                            Color(0xFF00E5FF).copy(alpha = 0.15f),
                                            Color.Transparent
                                        )
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = "AI Processing",
                                tint = Color(0xFF00E5FF),
                                modifier = Modifier.size(46.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(32.dp))

                    Text(
                        text = "xinntao / Real-ESRGAN",
                        color = Color(0xFF00E5FF),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = "${mode.label} AI Super Resolution",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Synthesizing high-frequency neural details with tile-based overlap stitching",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(28.dp))

                    // Progress bar
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        LinearProgressIndicator(
                            progress = { state.progress.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = Color(0xFF00E5FF),
                            trackColor = Color.White.copy(alpha = 0.12f)
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = state.stageMessage.ifEmpty { "Processing tiles..." },
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 12.5.sp,
                                maxLines = 1
                            )
                            Text(
                                text = "${(state.progress * 100).toInt()}%",
                                color = Color(0xFF00E5FF),
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Hardware Acceleration pill
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFF1E212B))
                            .border(1.dp, Color(0xFF00E5FF).copy(alpha = 0.35f), RoundedCornerShape(20.dp))
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Memory,
                            contentDescription = "Hardware Backend",
                            tint = if (state.isGpuActive) Color(0xFF00E5FF) else Color(0xFFFFB74D),
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = if (state.isGpuActive) "Hardware GPU Accelerated (OpenGL ES)" else "CPU Multi-threaded Fallback",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            // Top control bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.6f))
                        .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.White
                    )
                }

                FrostedGlassBox(
                    shape = RoundedCornerShape(16.dp),
                    elevation = 12.dp,
                    baseAlpha = 0.70f,
                    modifier = Modifier.padding(horizontal = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = if (currentUri != null) Icons.Default.CheckCircle else Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = if (currentUri != null) Color(0xFF69F0AE) else Color(0xFF00E5FF),
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = if (currentUri != null) "Real-ESRGAN ${mode.label} · Complete" else "Processing ${mode.label} AI Photo",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                if (currentUri != null) {
                    IconButton(
                        onClick = {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "image/jpeg"
                                putExtra(Intent.EXTRA_STREAM, currentUri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Share Super Resolution Photo"))
                        },
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.6f))
                            .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share",
                            tint = Color.White
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.size(42.dp))
                }
            }
        }
    }
}
