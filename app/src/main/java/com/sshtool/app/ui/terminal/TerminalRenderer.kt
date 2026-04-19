package com.sshtool.app.ui.terminal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import com.sshtool.app.terminal.ScreenSnapshot
import com.sshtool.app.terminal.TerminalEmulator

@Composable
fun TerminalRenderer(
    snapshot: ScreenSnapshot?,
    fontSize: Float,
    modifier: Modifier = Modifier,
    onSizeChanged: ((rows: Int, cols: Int) -> Unit)? = null,
    onTap: (() -> Unit)? = null
) {
    if (snapshot == null) return

    val density = LocalDensity.current
    val fontSizePx = with(density) { fontSize.sp.toPx() }

    val textPaint = remember(fontSize) {
        android.graphics.Paint().apply {
            isAntiAlias = true
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = fontSizePx
            color = android.graphics.Color.WHITE
        }
    }

    val charWidth = remember(fontSize) { textPaint.measureText("M") }
    val charHeight = remember(fontSize) {
        val fm = textPaint.fontMetrics
        fm.descent - fm.ascent
    }
    val baseline = remember(fontSize) { -textPaint.fontMetrics.ascent }

    var lastReportedSize by remember { mutableStateOf(Pair(0, 0)) }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures { onTap?.invoke() }
            }
    ) {
        val canvasWidth = size.width
        val canvasHeight = size.height

        // Calculate visible rows/cols based on canvas size
        val visibleCols = (canvasWidth / charWidth).toInt().coerceAtLeast(1)
        val visibleRows = (canvasHeight / charHeight).toInt().coerceAtLeast(1)

        // Notify size change
        val newSize = Pair(visibleRows, visibleCols)
        if (newSize != lastReportedSize && visibleRows > 0 && visibleCols > 0) {
            lastReportedSize = newSize
            onSizeChanged?.invoke(visibleRows, visibleCols)
        }

        // Draw background
        drawRect(
            color = Color(TerminalEmulator.DEFAULT_BG_COLOR),
            topLeft = Offset.Zero,
            size = Size(canvasWidth, canvasHeight)
        )

        // Draw cells
        val nativeCanvas = drawContext.canvas.nativeCanvas
        for (row in 0 until minOf(snapshot.rows, visibleRows, snapshot.cells.size)) {
            val rowCells = snapshot.cells[row]
            for (col in 0 until minOf(snapshot.cols, visibleCols, rowCells.size)) {
                val cell = rowCells[col]
                val x = col * charWidth
                val y = row * charHeight

                // Determine colors
                var fgColor = if (cell.fg == 7 && !cell.bold) TerminalEmulator.DEFAULT_FG_COLOR
                    else TerminalEmulator.resolveColor(cell.fg, cell.bold)
                var bgColor = if (cell.bg == 0) TerminalEmulator.DEFAULT_BG_COLOR
                    else TerminalEmulator.resolveColor(cell.bg)

                if (cell.inverse) {
                    val tmp = fgColor; fgColor = bgColor; bgColor = tmp
                }
                if (cell.dim) {
                    fgColor = dimColor(fgColor)
                }

                // Draw background if not default
                if (bgColor != TerminalEmulator.DEFAULT_BG_COLOR) {
                    drawRect(
                        color = Color(bgColor),
                        topLeft = Offset(x, y),
                        size = Size(charWidth, charHeight)
                    )
                }

                // Draw character
                if (cell.char != ' ' && cell.char != '\u0000') {
                    textPaint.color = fgColor
                    textPaint.isFakeBoldText = cell.bold
                    textPaint.isUnderlineText = cell.underline
                    textPaint.textSkewX = if (cell.italic) -0.25f else 0f

                    nativeCanvas.drawText(
                        cell.char.toString(),
                        x,
                        y + baseline,
                        textPaint
                    )
                }
            }
        }

        // Draw cursor
        if (snapshot.showCursor &&
            snapshot.cursorRow in 0 until snapshot.rows &&
            snapshot.cursorCol in 0 until snapshot.cols
        ) {
            val cursorX = snapshot.cursorCol * charWidth
            val cursorY = snapshot.cursorRow * charHeight
            drawRect(
                color = Color(TerminalEmulator.CURSOR_COLOR).copy(alpha = 0.7f),
                topLeft = Offset(cursorX, cursorY),
                size = Size(charWidth, charHeight)
            )
        }
    }
}

private fun dimColor(color: Int): Int {
    val a = (color shr 24) and 0xFF
    val r = ((color shr 16) and 0xFF) * 2 / 3
    val g = ((color shr 8) and 0xFF) * 2 / 3
    val b = (color and 0xFF) * 2 / 3
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}
