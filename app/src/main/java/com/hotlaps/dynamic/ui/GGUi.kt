// GGUi.kt — Step 3: single file that holds the screen AND the drawing.
// For now, we use placeholders. Next steps will:
//  • Hook to Settings values
//  • Wire “Go!” to navigate here
//  • Feed real sensor data + trail

package com.hotlaps.dynamic.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp

// For reading Settings the same way SettingsScreen does
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import com.hotlaps.dynamic.data.SettingsRepo

// --- GG wedge bands + diagonal labels (ported from HotLapMobile RacingScreen) ---
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.geometry.Size
import android.graphics.Paint
import android.graphics.Typeface
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import androidx.compose.ui.unit.dp
import android.util.Log
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GGScreen(
    modifier: Modifier = Modifier
) {
    // === Read Settings (same pattern as SettingsScreen) ===
    val context = LocalContext.current
    val repo = remember(context) { SettingsRepo(context) }

    // Collect the three settings we need
    val ggMaxG        by repo.ggMaxG.collectAsStateWithLifecycle(initialValue = 1.25f)
    val ggTrailWindow by repo.ggTrailWindowS.collectAsStateWithLifecycle(initialValue = 3.0f)
    val trailBrakeG   by repo.trailBrakeG.collectAsStateWithLifecycle(initialValue = 0.30f)


// === 10 Hz ticker + live g-values (TEMP simulation shows motion) ===
    var ticks by remember { mutableStateOf(0L) }
    var latG  by remember { mutableStateOf(0f) }
    var longG by remember { mutableStateOf(0f) }

    LaunchedEffect(Unit) {
        // 10 Hz loop (every 100 ms)
        while (true) {
            kotlinx.coroutines.delay(100)

            // ---- TEMP SIMULATION (replace with real sensor reads later) ----
            val t = ticks * 0.1f
            latG  = (0.85f * kotlin.math.sin(t.toDouble())).toFloat()
            longG = (0.65f * kotlin.math.cos(1.3f * t.toDouble())).toFloat()
            // ----------------------------------------------------------------

            ticks++  // drives GGPlot trail timing too
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("G-G") })
        }
    ) { inner ->
        Box(
            modifier = modifier
                .padding(inner)
                .fillMaxSize()
        ) {
            // Color the longitudinal readout by phase (Accel/Brake/Neutral)
            val accelColor =
                when {
                    longG >  trailBrakeG -> Color(0xFF34D399) // accelerating (green)
                    longG < -trailBrakeG -> Color(0xFFEF4444) // braking (red)
                    else                 -> Color(0xFFE5E7EB) // neutral (light gray)
                }

            GGPlot(
                maxAbsG = ggMaxG,
                latG = latG,
                longG = longG,
                trailSeconds = ggTrailWindow,
                ticks = ticks,
                brakeThreshG = trailBrakeG,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            )

            // --- Top-left HUD with the live numbers ---
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Longitudinal Accel (g) — color by braking/accelerating
                Text(
                    text = "Long Accel: ${"%.2f".format(longG)} g",
                    color = accelColor,
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Bold
                )

                // Lateral Accel (g) — bright blue for contrast
                Text(
                    text = "Lat Accel:  ${"%.2f".format(latG)} g",
                    color = Color(0xFF60A5FA),
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Bold
                )
            } // <-- closes Column
        } // <-- closes Box
    } // <-- closes Scaffold
}




@Composable
private fun GGPlot(
    maxAbsG: Float,
    latG: Float,
    longG: Float,
    trailSeconds: Float,
    ticks: Long,
    brakeThreshG: Float,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier
            .fillMaxWidth()
            .aspectRatio(1f)
    ) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = size.minDimension * 0.48f
        val textSizePx = size.minDimension * 0.045f
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.parseColor("#444444")
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = textSizePx
        }

        // ----- Base grid -----
        drawCircle(Color.Gray, radius, Offset(cx, cy), style = Stroke(width = 3f))
        drawLine(Color.Gray, Offset(cx - radius, cy), Offset(cx + radius, cy), 2f)
        drawLine(Color.Gray, Offset(cx, cy - radius), Offset(cx, cy + radius), 2f)

        // Tick rings at 0.5 G intervals up to maxAbsG
        val tickStep = 0.5f
        var tick = tickStep
        while (tick < maxAbsG) {
            val r = radius * (tick / maxAbsG)
            drawCircle(Color.DarkGray, r, Offset(cx, cy), style = Stroke(1f))
            tick += tickStep
        }

        // Center dot
        drawCircle(Color.White.copy(alpha = 0.7f), 5f, Offset(cx, cy))

        // Wedges + diagonal labels (ported)
        drawGgRadialsAndLabels(
            latG = latG,
            longG = longG,
            brakeThreshG = brakeThreshG,
            windowStartDeg = 20f,
            windowEndDeg = 70f,
            lineAlpha = 0.30f,
            strokeWidthDp = 1f
        )

        // Axis title labels (same look as HotLapMobile)
        drawGgLabels()

        // Optional numeric scale markers
        listOf(0.5f, 1.0f, 1.5f, 2.0f).filter { it <= maxAbsG }.forEach {
            val r = radius * (it / maxAbsG)
            drawContext.canvas.nativeCanvas.drawText(
                "${"%.1f".format(it)}G",
                cx + r - textSizePx * 0.6f,
                cy - textSizePx,
                paint
            )
        }

        // === Moving G-G dot (drawn last, above wedges) ===
        val toPx: (Float) -> Float = { g -> (g / maxAbsG) * radius }
        fun clampToCircle(xIn: Float, yIn: Float): Offset {
            var x = xIn
            var y = yIn
            val dx = x - cx
            val dy = y - cy
            val dist = kotlin.math.sqrt(dx * dx + dy * dy)
            if (dist > radius && dist > 0f) {
                val s = radius / dist
                x = cx + dx * s
                y = cy + dy * s
            }
            return Offset(x, y)
        }

        run {
            val px = cx + toPx(latG)   // +X → right
            val py = cy - toPx(longG)  // +Y → up
            val c  = clampToCircle(px, py)
            drawCircle(
                color = Color(0xFF1E88E5), // vivid blue
                radius = 18f,
                center = c
            )
        }
    }
}


/** Draw wedge boundaries at 20° and 70° in each quadrant, plus diagonal labels. */
fun DrawScope.drawGgRadialsAndLabels(
    latG: Float = 0f,
    longG: Float = 0f,
    brakeThreshG: Float = 0.2f,
    windowStartDeg: Float = 20f,
    windowEndDeg: Float = 70f,
    lineAlpha: Float = 0.30f,
    strokeWidthDp: Float = 1f
) {
    val w = size.width
    val h = size.height
    val cx = w / 2f
    val cy = h / 2f
    val radius = min(w, h) * 0.46f

    // Determine active region
    val isBraking = longG < -brakeThreshG
    val isAccelerating = longG > brakeThreshG
    val isLeft = latG < -0.05f
    val isRight = latG > 0.05f

    // ---- Wedge boundary lines at {20°, 70°} offset from each axis (0, 90, 180, 270)
    val stroke = strokeWidthDp.dp.toPx()
    val lineColor = Color(0xFF666666).copy(alpha = lineAlpha) // darker gray for white bg

    val bases = floatArrayOf(0f, 90f, 180f, 270f)
    val offsets = floatArrayOf(windowStartDeg, windowEndDeg)

    val arcTopLeft = Offset(cx - radius, cy - radius)
    val arcSize    = Size(radius * 2f, radius * 2f)

    val bandSweep = (windowEndDeg - windowStartDeg).coerceAtLeast(1f)

    val (shouldHighlight, startDeg, wedgeColor) = when {
        // Bottom-right (Right→Down): BRAKING + RIGHT
        isBraking && isRight -> Triple(true,   0f + windowStartDeg, Color(0xFF60A5FA).copy(alpha = 0.65f))
        // Bottom-left  (Down→Left):  BRAKING + LEFT
        isBraking && isLeft  -> Triple(true,  90f + windowStartDeg, Color(0xFF60A5FA).copy(alpha = 0.65f))
        // Top-right    (Up→Right):   ACCEL + RIGHT
        isAccelerating && isRight -> Triple(true, 270f + windowStartDeg, Color(0xFF34D399).copy(alpha = 0.55f))
        // Top-left     (Left→Up):    ACCEL + LEFT
        isAccelerating && isLeft  -> Triple(true, 180f + windowStartDeg, Color(0xFF34D399).copy(alpha = 0.55f))
        else -> Triple(false, 0f, Color.Transparent)
    }

    Log.d("GG-HILITE", "brake=$isBraking accel=$isAccelerating left=$isLeft right=$isRight long=$longG lat=$latG")

    if (shouldHighlight) {
        // Filled wedge (bold)
        drawArc(
            color = wedgeColor,
            startAngle = startDeg,
            sweepAngle = bandSweep,
            useCenter = true,
            topLeft = Offset(cx - radius, cy - radius),
            size = Size(radius * 2f, radius * 2f),
            style = Fill
        )
        // Thin outline so it "pops" against the grid
        drawArc(
            color = wedgeColor.copy(alpha = 0.9f),
            startAngle = startDeg,
            sweepAngle = bandSweep,
            useCenter = false,
            topLeft = Offset(cx - radius, cy - radius),
            size = Size(radius * 2f, radius * 2f),
            style = Stroke(width = 4.dp.toPx())
        )
    }

    // ===== PURE-AXIS HIGHLIGHTS (narrow wedges around each axis) =====
    val latNearZero = abs(latG) < 0.05f
    val longNearZero = abs(longG) < 0.05f
    val latThreshG = 0.10f                         // lateral threshold for pure left/right
    val narrowSweep = (windowStartDeg * 2f).coerceAtLeast(6f) // ~±windowStart around axis

    fun normAngle(deg: Float) = ((deg % 360f) + 360f) % 360f
    fun drawAxisWedge(centerDeg: Float, color: Color) {
        drawArc(
            color = color.copy(alpha = 0.85f),
            startAngle = normAngle(centerDeg - narrowSweep / 2f),
            sweepAngle = narrowSweep,
            useCenter = true,
            topLeft = arcTopLeft,
            size = arcSize,
            style = Fill
        )
        drawArc(
            color = Color.Black.copy(alpha = 0.9f),
            startAngle = normAngle(centerDeg - narrowSweep / 2f),
            sweepAngle = narrowSweep,
            useCenter = false,
            topLeft = arcTopLeft,
            size = arcSize,
            style = Stroke(width = 6.dp.toPx())
        )
    }

    // Up axis = 270°, Down = 90°, Right = 0°, Left = 180° (Android canvas)
    if (latNearZero && longG >  brakeThreshG) drawAxisWedge(270f, Color(0xFF06B6D4)) // Pure Accel (cyan)
    if (latNearZero && longG < -brakeThreshG) drawAxisWedge( 90f, Color(0xFFEF4444)) // Pure Brake (red)
    if (longNearZero && latG >  latThreshG)   drawAxisWedge(  0f, Color(0xFFF59E0B)) // Pure Right (orange)
    if (longNearZero && latG < -latThreshG)   drawAxisWedge(180f, Color(0xFFA855F7)) // Pure Left (violet)

    // --- Radial guideline lines (20° / 70° from each axis)
    for (base in bases) {
        for (off in offsets) {
            val deg = base + off
            val rad = Math.toRadians(deg.toDouble())
            val x = cx + radius * cos(rad).toFloat()
            val y = cy - radius * sin(rad).toFloat() // screen Y grows down
            drawLine(
                color = lineColor,
                start = Offset(cx, cy),
                end = Offset(x, y),
                strokeWidth = stroke
            )
        }
    }

    // ---- Diagonal labels along 45°/135°/−45°/−135°
    val diagPaint = Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.DKGRAY   // visible on white bg
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textSize = 18.dp.toPx()
        alpha = (255 * 0.70f).toInt()
    }

    fun drawDiagLabel(text: String, angleDeg: Float, rFrac: Float) {
        val rad = Math.toRadians(angleDeg.toDouble())
        val px = cx + (radius * rFrac) * cos(rad).toFloat()
        val py = cy - (radius * rFrac) * sin(rad).toFloat()
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.save()
            canvas.nativeCanvas.rotate(-angleDeg, px, py)
            canvas.nativeCanvas.drawText(text, px, py, diagPaint)
            canvas.nativeCanvas.restore()
        }
    }

    val rFrac = 0.72f
    // Accelerating quadrants
    drawDiagLabel("Throttle Steering",  45f, rFrac)
    drawDiagLabel("Throttle Steering", 135f, rFrac)
    // Braking quadrants
    drawDiagLabel("Trail Braking",    -45f, rFrac)
    drawDiagLabel("Trail Braking",   -135f, rFrac)
}


fun DrawScope.drawGgLabels() {
    val w = size.width
    val h = size.height
    val cx = w / 2f
    val cy = h / 2f

    val paint = Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.BLUE   // same as HotLapMobile for now
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    val axisSizePx = 18.sp.toPx()
    val quadSizePx = 14.sp.toPx()
    val faint = 0.40f
    val strong = 0.80f

    drawIntoCanvas { canvas ->
        // Axis labels (Y: Accelerating/Braking; X: Left/Right)
        paint.textSize = axisSizePx

        // Y axis
        paint.alpha = (255 * strong).roundToInt()
        canvas.nativeCanvas.drawText("Pure Acceleration", cx, 16.sp.toPx() + 8f, paint)
        canvas.nativeCanvas.drawText("Pure Braking",      cx, h - 8f,                 paint)

        // X axis: Left
        canvas.nativeCanvas.save()
        canvas.nativeCanvas.rotate(-90f, 16.sp.toPx() + 8f, cy)
        canvas.nativeCanvas.drawText("Pure Left", 16.sp.toPx() + 8f, cy, paint)
        canvas.nativeCanvas.restore()

        // X axis: Right
        canvas.nativeCanvas.save()
        canvas.nativeCanvas.rotate(90f, w - (16.sp.toPx() + 8f), cy)
        canvas.nativeCanvas.drawText("Pure Right", w - (16.sp.toPx() + 8f), cy, paint)
        canvas.nativeCanvas.restore()

        // Quadrant labels (kept commented, same as your source)
        paint.textSize = quadSizePx
        paint.alpha = (255 * faint).roundToInt()
        /*
        canvas.nativeCanvas.drawText("Throttle Steering", cx - w*0.35f, cy - h*0.25f, paint)
        canvas.nativeCanvas.drawText("Throttle Steering", cx + w*0.35f, cy - h*0.25f, paint)
        canvas.nativeCanvas.drawText("Trail Braking",     cx - w*0.35f, cy + h*0.25f, paint)
        canvas.nativeCanvas.drawText("Trail Braking",     cx + w*0.35f, cy + h*0.25f, paint)
        */
    }
}