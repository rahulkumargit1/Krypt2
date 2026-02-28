package com.krypt.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

@Composable
fun CallScreen(
    viewModel: KryptViewModel,
    remoteUuid: String,
    onEndCall: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val callState = uiState.callState
    var isMuted by remember { mutableStateOf(false) }
    var isCameraOff by remember { mutableStateOf(false) }

    // Hold renderer refs so we can attach tracks when ready
    var localRenderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }
    var remoteRenderer by remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    // When manager appears, attach local view
    LaunchedEffect(viewModel.webRTCManager, localRenderer) {
        val mgr = viewModel.webRTCManager ?: return@LaunchedEffect
        val lr = localRenderer ?: return@LaunchedEffect
        mgr.attachLocalView(lr)
    }

    // When remote track arrives, attach it
    LaunchedEffect(viewModel.webRTCManager, remoteRenderer) {
        val mgr = viewModel.webRTCManager ?: return@LaunchedEffect
        val rr = remoteRenderer ?: return@LaunchedEffect
        // If remote track already arrived, attach immediately
        mgr.remoteVideoTrack?.let { track ->
            try { track.addSink(rr) } catch (_: Exception) {}
        }
        mgr.attachRemoteView(rr)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(KryptBlack)
    ) {
        // Remote video — full screen background
        AndroidView(
            factory = { ctx ->
                SurfaceViewRenderer(ctx).also { renderer ->
                    remoteRenderer = renderer
                    viewModel.webRTCManager?.attachRemoteView(renderer)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Incoming call overlay
        if (callState.isIncoming && callState.pendingOfferSdp.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(KryptBlack.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    Text("📞", fontSize = 56.sp)
                    Text("Incoming Call", color = KryptText, fontSize = 28.sp)
                    Text(
                        text = remoteUuid.take(20) + "…",
                        color = KryptSubtext,
                        fontSize = 13.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                        // Decline
                        IconButton(
                            onClick = onEndCall,
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(Color.Red)
                        ) {
                            Icon(Icons.Default.CallEnd, contentDescription = "Decline",
                                tint = Color.White, modifier = Modifier.size(32.dp))
                        }
                        // Accept
                        IconButton(
                            onClick = { viewModel.acceptCall() },
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF00C853))
                        ) {
                            Icon(Icons.Default.Call, contentDescription = "Accept",
                                tint = Color.White, modifier = Modifier.size(32.dp))
                        }
                    }
                }
            }
        } else {
            // Active call UI

            // Local video PiP — top right
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .size(width = 100.dp, height = 140.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(KryptDark)
            ) {
                AndroidView(
                    factory = { ctx ->
                        SurfaceViewRenderer(ctx).also { renderer ->
                            localRenderer = renderer
                            viewModel.webRTCManager?.attachLocalView(renderer)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                if (isCameraOff) {
                    Box(Modifier.fillMaxSize().background(KryptCard),
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.VideocamOff, tint = KryptSubtext,
                            contentDescription = null, modifier = Modifier.size(32.dp))
                    }
                }
            }

            // Top info bar
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 16.dp, top = 52.dp)
                    .background(KryptBlack.copy(alpha = 0.5f), MaterialTheme.shapes.small)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("🔒", fontSize = 14.sp)
                Text("E2EE  •  ${remoteUuid.take(12)}…", color = KryptText, fontSize = 13.sp)
            }

            // Bottom controls
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 52.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CallButton(
                    icon = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                    tint = if (isMuted) Color.Red else KryptText,
                    onClick = {
                        isMuted = !isMuted
                        viewModel.webRTCManager?.toggleMute(isMuted)
                    }
                )
                // End call
                IconButton(
                    onClick = onEndCall,
                    modifier = Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(Color.Red)
                ) {
                    Icon(Icons.Default.CallEnd, contentDescription = "End Call",
                        tint = Color.White, modifier = Modifier.size(32.dp))
                }
                CallButton(
                    icon = if (isCameraOff) Icons.Default.VideocamOff else Icons.Default.Videocam,
                    tint = if (isCameraOff) Color.Red else KryptText,
                    onClick = {
                        isCameraOff = !isCameraOff
                        viewModel.webRTCManager?.toggleCamera(isCameraOff)
                    }
                )
            }
        }
    }
}

@Composable
fun CallButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color = KryptText,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(KryptCard)
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(26.dp))
    }
}
