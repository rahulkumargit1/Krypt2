package com.krypt.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.delay
import kotlin.random.Random

val KryptBlack = Color(0xFF000000)
val KryptDark = Color(0xFF0D0D0D)
val KryptCard = Color(0xFF1A1A1A)
val KryptAccent = Color(0xFF00E5FF)
val KryptText = Color(0xFFFFFFFF)
val KryptSubtext = Color(0xFF888888)
val MatrixGreen = Color(0xFF00FF41)

private val KryptDarkColorScheme = darkColorScheme(
    primary = KryptAccent,
    onPrimary = KryptBlack,
    background = KryptBlack,
    onBackground = KryptText,
    surface = KryptDark,
    onSurface = KryptText,
    surfaceVariant = KryptCard,
    onSurfaceVariant = KryptText,
    secondary = KryptAccent,
    onSecondary = KryptBlack
)

class MainActivity : ComponentActivity() {

    private val viewModel: KryptViewModel by viewModels {
        KryptViewModel.Factory(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = KryptDarkColorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    KryptApp(viewModel = viewModel)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        NetworkClient.disconnect()
    }
}

// ─── App Root with Splash ──────────────────────────────────────────────────────

@Composable
fun KryptApp(viewModel: KryptViewModel) {
    var showSplash by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        delay(3000)
        showSplash = false
    }

    if (showSplash) {
        SplashScreen()
    } else {
        KryptNavGraph(viewModel = viewModel)
    }
}

// ─── Matrix Rain Splash ────────────────────────────────────────────────────────

private val MATRIX_CHARS = "アイウエオカキクケコサシスセソタチツテトナニヌネノ0123456789ABCDEFKRYPT"

data class MatrixColumn(
    val x: Float,
    var y: Float,
    val speed: Float,
    val charIndices: List<Int>,
    var headIndex: Int = 0
)

@Composable
fun SplashScreen() {
    val infiniteTransition = rememberInfiniteTransition(label = "matrix")
    val frame by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(50, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "frame"
    )

    // Glitch animation for "KRYPT" title
    val glitchOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6f,
        animationSpec = infiniteRepeatable(
            animation = tween(80, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glitch"
    )

    // Title alpha pulse
    val titleAlpha by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    // Matrix columns state
    val columns = remember { mutableStateListOf<MatrixColumn>() }
    var screenWidth by remember { mutableStateOf(0f) }
    var screenHeight by remember { mutableStateOf(0f) }

    // Advance matrix on each frame tick
    LaunchedEffect(frame) {
        if (screenWidth > 0 && columns.isNotEmpty()) {
            columns.forEachIndexed { i, col ->
                col.y += col.speed
                if (col.y > screenHeight + 200f) {
                    columns[i] = col.copy(
                        y = -Random.nextFloat() * 400f,
                        speed = Random.nextFloat() * 8f + 4f,
                        headIndex = (col.headIndex + 1) % col.charIndices.size
                    )
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(KryptBlack)
    ) {
        // Matrix rain canvas
        Canvas(
            modifier = Modifier
                .fillMaxSize()
        ) {
            if (screenWidth == 0f) {
                screenWidth = size.width
                screenHeight = size.height
                val colCount = (size.width / 22).toInt()
                repeat(colCount) { i ->
                    columns.add(
                        MatrixColumn(
                            x = i * 22f,
                            y = -Random.nextFloat() * size.height,
                            speed = Random.nextFloat() * 8f + 4f,
                            charIndices = List(30) { Random.nextInt(MATRIX_CHARS.length) }
                        )
                    )
                }
            }

            columns.forEach { col ->
                // Draw trailing characters fading out
                for (k in 0 until 20) {
                    val charY = col.y - k * 22f
                    if (charY < 0 || charY > size.height) continue
                    val alpha = ((20 - k) / 20f) * 0.6f
                    val charIdx = (col.headIndex + k) % col.charIndices.size
                    val char = MATRIX_CHARS[col.charIndices[charIdx] % MATRIX_CHARS.length]
                    drawContext.canvas.nativeCanvas.drawText(
                        char.toString(),
                        col.x,
                        charY,
                        android.graphics.Paint().apply {
                            color = android.graphics.Color.argb(
                                (alpha * 255).toInt(),
                                0, 255, 65
                            )
                            textSize = 16f
                            typeface = android.graphics.Typeface.MONOSPACE
                        }
                    )
                }
                // Bright head character
                if (col.y in 0f..size.height) {
                    val headChar = MATRIX_CHARS[col.charIndices[col.headIndex % col.charIndices.size] % MATRIX_CHARS.length]
                    drawContext.canvas.nativeCanvas.drawText(
                        headChar.toString(),
                        col.x,
                        col.y,
                        android.graphics.Paint().apply {
                            color = android.graphics.Color.argb(255, 200, 255, 220)
                            textSize = 16f
                            typeface = android.graphics.Typeface.MONOSPACE
                        }
                    )
                }
            }
        }

        // Dark overlay so title is readable
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(KryptBlack.copy(alpha = 0.45f))
        )

        // Center content
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(x = if ((frame * 10).toInt() % 7 == 0) glitchOffset.dp else 0.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Lock icon
            Text(
                text = "🔐",
                fontSize = 56.sp
            )
            Spacer(Modifier.height(16.dp))

            // KRYPT title with glitch
            Box {
                // Glitch layer (red offset)
                Text(
                    text = "KRYPT",
                    fontSize = 64.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    color = Color.Red.copy(alpha = 0.3f),
                    modifier = Modifier.offset(x = 3.dp, y = (-2).dp)
                )
                // Glitch layer (cyan offset)
                Text(
                    text = "KRYPT",
                    fontSize = 64.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    color = KryptAccent.copy(alpha = 0.3f),
                    modifier = Modifier.offset(x = (-3).dp, y = 2.dp)
                )
                // Main title
                Text(
                    text = "KRYPT",
                    fontSize = 64.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    color = KryptText.copy(alpha = titleAlpha)
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = "end-to-end encrypted",
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                color = KryptAccent.copy(alpha = 0.8f),
                letterSpacing = 4.sp
            )
        }

        // Bottom — "Created by Rahul" matrix glitch style
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Glitching matrix text
            val glitchChars = remember { mutableStateOf("") }
            val targetText = "created by Rahul"
            LaunchedEffect(Unit) {
                while (true) {
                    // Glitch phase — random matrix chars
                    repeat(8) {
                        glitchChars.value = (targetText.indices).map {
                            if (Random.nextFloat() > 0.5f)
                                MATRIX_CHARS[Random.nextInt(MATRIX_CHARS.length)]
                            else targetText[it]
                        }.joinToString("")
                        delay(60)
                    }
                    // Resolve phase
                    glitchChars.value = targetText
                    delay(2000)
                }
            }
            Text(
                text = glitchChars.value.ifEmpty { targetText },
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = MatrixGreen.copy(alpha = 0.85f),
                letterSpacing = 2.sp
            )
            Spacer(Modifier.height(6.dp))
            // Blinking cursor
            val cursorVisible by infiniteTransition.animateFloat(
                initialValue = 0f, targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(500), repeatMode = RepeatMode.Reverse
                ),
                label = "cursor"
            )
            Text(
                text = "█",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = MatrixGreen.copy(alpha = cursorVisible)
            )
        }
    }
}

// ─── Nav Graph ─────────────────────────────────────────────────────────────────

@Composable
fun KryptNavGraph(viewModel: KryptViewModel) {
    val navController = rememberNavController()
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.callState.isInCall) {
        if (uiState.callState.isInCall) {
            navController.navigate("call/${uiState.callState.remoteUuid}") {
                launchSingleTop = true
            }
        }
    }

    NavHost(navController = navController, startDestination = "contacts") {
        composable("contacts") {
            ContactsScreen(
                viewModel = viewModel,
                onOpenChat = { uuid ->
                    viewModel.openConversation(uuid)
                    navController.navigate("chat/$uuid")
                },
                onOpenStatus = { navController.navigate("status") }
            )
        }
        composable(
            route = "chat/{uuid}",
            arguments = listOf(navArgument("uuid") { type = NavType.StringType })
        ) { backStack ->
            val uuid = backStack.arguments?.getString("uuid") ?: return@composable
            ChatScreen(
                viewModel = viewModel,
                contactUuid = uuid,
                onStartCall = { viewModel.startCall(uuid) },
                onBack = { navController.popBackStack() }
            )
        }
        composable(
            route = "call/{uuid}",
            arguments = listOf(navArgument("uuid") { type = NavType.StringType })
        ) { backStack ->
            val uuid = backStack.arguments?.getString("uuid") ?: return@composable
            CallScreen(
                viewModel = viewModel,
                remoteUuid = uuid,
                onEndCall = {
                    viewModel.endCall()
                    navController.popBackStack()
                }
            )
        }
        composable("status") {
            StatusScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
