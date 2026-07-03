package chat.keryx.app.presentation.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun HermesThinkingAnimation(
    style: String = "Braille",
    modifier: Modifier = Modifier
) {
    // Subtle pulsing alpha on the whole component
    val infiniteTransition = rememberInfiniteTransition(label = "snake_spin")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha_pulse"
    )

    val phrases = listOf(
        "SYNTHESIZING",
        "ALIGNING TENSORS",
        "CRUNCHING DATA",
        "CONSULTING ORACLE",
        "MODELING CONTEXT",
        "COMPILING REALITY",
        "DEFRAGGING THOUGHTS",
        "INJECTING LOGIC",
        "BENDING TIME",
        "FETCHING ANSWERS",
        "CALIBRATING NEURONS",
        "PARSING REQUEST",
        "OPTIMIZING ALGORITHMS",
        "QUERYING THE VOID",
        "ASSEMBLING RESPONSE",
        "GATHERING INSIGHTS",
        "RUNNING DIAGNOSTICS",
        "SPINNING WEB OF DATA",
        "DECODING INTENT",
        "MAPPING THE MULTIVERSE",
        "QUANTUM ROUTING",
        "LOADING BRILLIANCE",
        "REBOOTING MATRIX",
        "GENERATING MAGIC"
    )
    val quip = remember { phrases.random() }
    val primaryColor = MaterialTheme.colorScheme.primary
    val accent2 = MaterialTheme.colorScheme.tertiary

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .alpha(alpha)
    ) {
        when (style) {
            "Braille" -> BrailleSpinner(primaryColor, accent2)
            "Dots" -> DotsSpinner(primaryColor, accent2, infiniteTransition)
            "ASCII Wave" -> AsciiWaveSpinner()
            else -> BrailleSpinner(primaryColor, accent2)
        }
        
        Spacer(modifier = Modifier.width(12.dp))
        
        Text(
            text = quip,
            style = androidx.compose.ui.text.TextStyle(
                // Accent 1 → accent 2 sweep across the quip, matching the spinner's gradient.
                brush = androidx.compose.ui.graphics.Brush.linearGradient(
                    listOf(primaryColor, accent2),
                ),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                letterSpacing = 1.2.sp,
            ),
        )
    }
}

@Composable
fun BrailleSpinner(primaryColor: Color, accent2: Color = primaryColor) {
    // A perfect, constant-length (3 dots) snake traveling the perimeter of a 2x3 Braille grid
    val frames = listOf(
        listOf(0, 3, 4), // ⠩
        listOf(3, 4, 5), // ⠸
        listOf(4, 5, 2), // ⠴
        listOf(5, 2, 1), // ⠦
        listOf(2, 1, 0), // ⠇
        listOf(1, 0, 3)  // ⠙
    )
    var frameIndex by remember { mutableStateOf(0) }
    
    LaunchedEffect(Unit) {
        while (true) {
            delay(100) 
            frameIndex = (frameIndex + 1) % frames.size
        }
    }
    
    Box(
        modifier = Modifier.size(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(10.dp, 16.dp)) { 
            val dotRadius = 1.5.dp.toPx()
            
            // 2x3 Grid positions mimicking Braille
            val positions = listOf(
                Offset(dotRadius, dotRadius),                                // 0: Top-Left
                Offset(dotRadius, size.height / 2),                          // 1: Mid-Left
                Offset(dotRadius, size.height - dotRadius),                  // 2: Bot-Left
                Offset(size.width - dotRadius, dotRadius),                   // 3: Top-Right
                Offset(size.width - dotRadius, size.height / 2),             // 4: Mid-Right
                Offset(size.width - dotRadius, size.height - dotRadius)      // 5: Bot-Right
            )
            
            val activeDots = frames[frameIndex]

            // The 3-dot snake grades head→tail from accent 1 into accent 2.
            activeDots.forEachIndexed { order, dot ->
                drawCircle(
                    color = androidx.compose.ui.graphics.lerp(primaryColor, accent2, order / 2f),
                    radius = dotRadius,
                    center = positions[dot]
                )
            }
        }
    }
}

@Composable
fun AsciiWaveSpinner() {
    val frames = listOf(" ", "▂", "▃", "▄", "▅", "▆", "▇", "█", "▇", "▆", "▅", "▄", "▃", "▂")
    var frameIndex by remember { mutableStateOf(0) }
    
    LaunchedEffect(Unit) {
        while (true) {
            delay(100)
            frameIndex = (frameIndex + 1) % frames.size
        }
    }
    
    Box(
        modifier = Modifier.defaultMinSize(minWidth = 24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = frames[frameIndex],
            color = MaterialTheme.colorScheme.primary,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )
    }
}

@Composable
fun DotsSpinner(primaryColor: Color, accent2: Color = primaryColor, infiniteTransition: InfiniteTransition) {
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )
    
    Box(
        modifier = Modifier.size(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(16.dp)) {
            val radius = size.minDimension / 2
            val center = Offset(size.width / 2, size.height / 2)
            
            // Draw a snake of 6 dots fading in opacity
            val numDots = 6
            for (i in 0 until numDots) {
                // Offset each dot backward in the circle
                val dotAngle = rotation - (i * (PI / 5).toFloat())
                
                val x = center.x + radius * cos(dotAngle)
                val y = center.y + radius * sin(dotAngle)
                
                // The head of the snake is brightest and largest
                val dotAlpha = 1f - (i * 0.15f)
                val dotRadius = 3.dp.toPx() - (i * 0.3f.dp.toPx())
                
                drawCircle(
                    color = androidx.compose.ui.graphics.lerp(primaryColor, accent2, i / (numDots - 1f))
                        .copy(alpha = dotAlpha.coerceIn(0f, 1f)),
                    radius = dotRadius,
                    center = Offset(x, y)
                )
            }
        }
    }
}
