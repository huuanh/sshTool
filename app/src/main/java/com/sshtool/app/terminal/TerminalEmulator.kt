package com.sshtool.app.terminal

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.ArrayDeque

data class TerminalCell(
    var char: Char = ' ',
    var fg: Int = 7,
    var bg: Int = 0,
    var bold: Boolean = false,
    var italic: Boolean = false,
    var underline: Boolean = false,
    var inverse: Boolean = false,
    var dim: Boolean = false
) {
    fun reset() {
        char = ' '; fg = 7; bg = 0
        bold = false; italic = false; underline = false; inverse = false; dim = false
    }

    fun copyFrom(other: TerminalCell) {
        char = other.char; fg = other.fg; bg = other.bg
        bold = other.bold; italic = other.italic
        underline = other.underline; inverse = other.inverse; dim = other.dim
    }
}

data class ScreenSnapshot(
    val cells: Array<Array<TerminalCell>>,
    val cursorRow: Int,
    val cursorCol: Int,
    val showCursor: Boolean,
    val title: String,
    val rows: Int,
    val cols: Int
)

class TerminalEmulator(
    var rows: Int = 24,
    var cols: Int = 80
) {
    private val lock = Any()
    private var screen: Array<Array<TerminalCell>> = createBuffer(rows, cols)
    private val scrollback = ArrayDeque<Array<TerminalCell>>(5000)
    private val maxScrollback = 5000

    private var cursorRow = 0
    private var cursorCol = 0
    private var savedCursorRow = 0
    private var savedCursorCol = 0

    // Text attributes
    private var currentFg = 7
    private var currentBg = 0
    private var currentBold = false
    private var currentItalic = false
    private var currentUnderline = false
    private var currentInverse = false
    private var currentDim = false

    // Modes
    private var showCursor = true
    private var applicationCursorKeys = false
    private var autoWrap = true
    private var originMode = false
    private var insertMode = false

    // Alternate screen
    private var altScreen: Array<Array<TerminalCell>>? = null
    private var altCursorRow = 0
    private var altCursorCol = 0

    // Scroll region
    private var scrollTop = 0
    private var scrollBottom = rows - 1

    // Parser state
    private var state = ParseState.GROUND
    private val paramBuffer = StringBuilder()
    private val intermediateBuffer = StringBuilder()
    private var oscBuffer = StringBuilder()

    var title = ""
        set(value) { synchronized(lock) { field = value } }

    // Charset (simple)
    private var useLineDrawing = false

    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version

    private enum class ParseState {
        GROUND, ESC, CSI_ENTRY, CSI_PARAM, CSI_INTERMEDIATE, OSC, OSC_STRING, DCS, CHARSET
    }

    private fun createBuffer(r: Int, c: Int): Array<Array<TerminalCell>> =
        Array(r) { Array(c) { TerminalCell() } }

    private fun notifyUpdate() {
        _version.value++
    }

    fun processBytes(data: ByteArray, offset: Int, length: Int) {
        synchronized(lock) {
            for (i in offset until offset + length) {
                processByte(data[i].toInt() and 0xFF)
            }
            notifyUpdate()
        }
    }

    fun appendText(text: String) {
        processBytes(text.toByteArray(), 0, text.toByteArray().size)
    }

    fun getSnapshot(): ScreenSnapshot {
        synchronized(lock) {
            val copy = Array(rows) { r ->
                Array(cols) { c ->
                    if (r < screen.size && c < screen[r].size) {
                        TerminalCell().also { it.copyFrom(screen[r][c]) }
                    } else {
                        TerminalCell()
                    }
                }
            }
            return ScreenSnapshot(copy, cursorRow, cursorCol, showCursor, title, rows, cols)
        }
    }

    fun resize(newRows: Int, newCols: Int) {
        if (newRows <= 0 || newCols <= 0) return
        synchronized(lock) {
            val newScreen = createBuffer(newRows, newCols)
            val copyRows = minOf(rows, newRows)
            val copyCols = minOf(cols, newCols)
            for (r in 0 until copyRows) {
                for (c in 0 until copyCols) {
                    newScreen[r][c].copyFrom(screen[r][c])
                }
            }
            screen = newScreen
            rows = newRows
            cols = newCols
            scrollTop = 0
            scrollBottom = newRows - 1
            cursorRow = minOf(cursorRow, newRows - 1)
            cursorCol = minOf(cursorCol, newCols - 1)
            notifyUpdate()
        }
    }

    private fun processByte(b: Int) {
        when (state) {
            ParseState.GROUND -> processGround(b)
            ParseState.ESC -> processEsc(b)
            ParseState.CSI_ENTRY, ParseState.CSI_PARAM -> processCsi(b)
            ParseState.CSI_INTERMEDIATE -> processCsiIntermediate(b)
            ParseState.OSC, ParseState.OSC_STRING -> processOsc(b)
            ParseState.DCS -> processDcs(b)
            ParseState.CHARSET -> processCharset(b)
        }
    }

    private fun processGround(b: Int) {
        when (b) {
            0x1B -> { state = ParseState.ESC }
            0x07 -> { /* BEL */ }
            0x08 -> { if (cursorCol > 0) cursorCol-- } // BS
            0x09 -> { cursorCol = minOf(((cursorCol / 8) + 1) * 8, cols - 1) } // TAB
            0x0A, 0x0B, 0x0C -> lineFeed() // LF, VT, FF
            0x0D -> { cursorCol = 0 } // CR
            0x0E -> { useLineDrawing = true }
            0x0F -> { useLineDrawing = false }
            in 0x20..0x7E -> putChar(b.toChar())
            in 0x80..0xFF -> putChar(b.toChar()) // Extended chars
        }
    }

    private fun processEsc(b: Int) {
        when (b) {
            0x5B -> { // [
                state = ParseState.CSI_ENTRY
                paramBuffer.clear()
                intermediateBuffer.clear()
            }
            0x5D -> { // ]
                state = ParseState.OSC
                oscBuffer.clear()
            }
            0x28 -> { state = ParseState.CHARSET } // ( - G0 charset
            0x29 -> { state = ParseState.CHARSET } // ) - G1 charset
            0x37 -> { saveCursor(); state = ParseState.GROUND } // 7 - save cursor
            0x38 -> { restoreCursor(); state = ParseState.GROUND } // 8 - restore cursor
            0x44 -> { lineFeed(); state = ParseState.GROUND } // D - index
            0x45 -> { cursorCol = 0; lineFeed(); state = ParseState.GROUND } // E - NEL
            0x4D -> { reverseLineFeed(); state = ParseState.GROUND } // M - reverse index
            0x50 -> { state = ParseState.DCS } // P - DCS
            0x63 -> { fullReset(); state = ParseState.GROUND } // c - RIS
            else -> {
                paramBuffer.clear()
                intermediateBuffer.clear()
                state = ParseState.GROUND
            }
        }
    }

    private fun processCsi(b: Int) {
        when {
            b in 0x30..0x3F -> { // parameter bytes
                paramBuffer.append(b.toChar())
                state = ParseState.CSI_PARAM
            }
            b in 0x20..0x2F -> { // intermediate bytes
                intermediateBuffer.append(b.toChar())
                state = ParseState.CSI_INTERMEDIATE
            }
            b in 0x40..0x7E -> { // final byte
                executeCsi(b.toChar())
                state = ParseState.GROUND
            }
            else -> { state = ParseState.GROUND }
        }
    }

    private fun processCsiIntermediate(b: Int) {
        when {
            b in 0x20..0x2F -> intermediateBuffer.append(b.toChar())
            b in 0x40..0x7E -> {
                executeCsi(b.toChar())
                state = ParseState.GROUND
            }
            else -> state = ParseState.GROUND
        }
    }

    private fun processOsc(b: Int) {
        when (b) {
            0x07 -> { executeOsc(); state = ParseState.GROUND } // BEL terminates
            0x1B -> { state = ParseState.OSC_STRING } // ESC might be ST
            else -> {
                oscBuffer.append(b.toChar())
                state = ParseState.OSC
            }
        }
    }

    private fun processDcs(b: Int) {
        if (b == 0x1B || b == 0x07) state = ParseState.GROUND
    }

    private fun processCharset(b: Int) {
        when (b.toChar()) {
            '0' -> useLineDrawing = true
            'B' -> useLineDrawing = false
        }
        state = ParseState.GROUND
    }

    private fun putChar(c: Char) {
        val ch = if (useLineDrawing) mapLineDrawing(c) else c

        if (cursorCol >= cols) {
            if (autoWrap) {
                cursorCol = 0
                lineFeed()
            } else {
                cursorCol = cols - 1
            }
        }

        if (cursorRow < 0 || cursorRow >= rows || cursorCol < 0 || cursorCol >= cols) return

        val cell = screen[cursorRow][cursorCol]
        cell.char = ch
        cell.fg = currentFg
        cell.bg = currentBg
        cell.bold = currentBold
        cell.italic = currentItalic
        cell.underline = currentUnderline
        cell.inverse = currentInverse
        cell.dim = currentDim
        cursorCol++
    }

    private fun mapLineDrawing(c: Char): Char = when (c) {
        'j' -> '┘'; 'k' -> '┐'; 'l' -> '┌'; 'm' -> '└'
        'n' -> '┼'; 'q' -> '─'; 't' -> '├'; 'u' -> '┤'
        'v' -> '┴'; 'w' -> '┬'; 'x' -> '│'; 'a' -> '▒'
        else -> c
    }

    private fun lineFeed() {
        if (cursorRow == scrollBottom) {
            scrollUp()
        } else if (cursorRow < rows - 1) {
            cursorRow++
        }
    }

    private fun reverseLineFeed() {
        if (cursorRow == scrollTop) {
            scrollDown()
        } else if (cursorRow > 0) {
            cursorRow--
        }
    }

    private fun scrollUp() {
        if (scrollTop < 0 || scrollTop >= rows || scrollBottom < 0 || scrollBottom >= rows) return
        // Save top line to scrollback
        val line = Array(cols) { TerminalCell().also { tc -> tc.copyFrom(screen[scrollTop][it]) } }
        scrollback.addLast(line)
        if (scrollback.size > maxScrollback) scrollback.removeFirst()

        for (r in scrollTop until scrollBottom) {
            for (c in 0 until cols) {
                screen[r][c].copyFrom(screen[r + 1][c])
            }
        }
        clearLine(scrollBottom)
    }

    private fun scrollDown() {
        if (scrollTop < 0 || scrollTop >= rows || scrollBottom < 0 || scrollBottom >= rows) return
        for (r in scrollBottom downTo scrollTop + 1) {
            for (c in 0 until cols) {
                screen[r][c].copyFrom(screen[r - 1][c])
            }
        }
        clearLine(scrollTop)
    }

    private fun clearLine(row: Int) {
        if (row < 0 || row >= rows) return
        for (c in 0 until cols) {
            screen[row][c].reset()
        }
    }

    private fun executeCsi(final: Char) {
        val params = parseParams()
        val isPrivate = paramBuffer.startsWith("?")

        when (final) {
            'A' -> { // Cursor Up
                val n = maxOf(params.getOrElse(0) { 1 }, 1)
                cursorRow = maxOf(cursorRow - n, scrollTop)
            }
            'B' -> { // Cursor Down
                val n = maxOf(params.getOrElse(0) { 1 }, 1)
                cursorRow = minOf(cursorRow + n, scrollBottom)
            }
            'C' -> { // Cursor Forward
                val n = maxOf(params.getOrElse(0) { 1 }, 1)
                cursorCol = minOf(cursorCol + n, cols - 1)
            }
            'D' -> { // Cursor Back
                val n = maxOf(params.getOrElse(0) { 1 }, 1)
                cursorCol = maxOf(cursorCol - n, 0)
            }
            'E' -> { // Cursor Next Line
                val n = maxOf(params.getOrElse(0) { 1 }, 1)
                cursorRow = minOf(cursorRow + n, scrollBottom)
                cursorCol = 0
            }
            'F' -> { // Cursor Previous Line
                val n = maxOf(params.getOrElse(0) { 1 }, 1)
                cursorRow = maxOf(cursorRow - n, scrollTop)
                cursorCol = 0
            }
            'G' -> { // Cursor Horizontal Absolute
                val n = maxOf(params.getOrElse(0) { 1 }, 1)
                cursorCol = minOf(n - 1, cols - 1)
            }
            'H', 'f' -> { // Cursor Position
                val r = maxOf(params.getOrElse(0) { 1 }, 1)
                val c = maxOf(params.getOrElse(1) { 1 }, 1)
                cursorRow = minOf(r - 1, rows - 1)
                cursorCol = minOf(c - 1, cols - 1)
            }
            'J' -> { // Erase in Display
                when (params.getOrElse(0) { 0 }) {
                    0 -> eraseFromCursor()
                    1 -> eraseToCursor()
                    2, 3 -> eraseScreen()
                }
            }
            'K' -> { // Erase in Line
                when (params.getOrElse(0) { 0 }) {
                    0 -> eraseLineFromCursor()
                    1 -> eraseLineToCursor()
                    2 -> clearLine(cursorRow)
                }
            }
            'L' -> { // Insert Lines
                val n = maxOf(params.getOrElse(0) { 1 }, 1)
                repeat(n) { insertLine() }
            }
            'M' -> { // Delete Lines
                val n = maxOf(params.getOrElse(0) { 1 }, 1)
                repeat(n) { deleteLine() }
            }
            'P' -> { // Delete Characters
                val n = maxOf(params.getOrElse(0) { 1 }, 1)
                deleteChars(n)
            }
            'S' -> { // Scroll Up
                val n = maxOf(params.getOrElse(0) { 1 }, 1)
                repeat(n) { scrollUp() }
            }
            'T' -> { // Scroll Down
                val n = maxOf(params.getOrElse(0) { 1 }, 1)
                repeat(n) { scrollDown() }
            }
            'X' -> { // Erase Characters
                val n = maxOf(params.getOrElse(0) { 1 }, 1)
                if (cursorRow in 0 until rows) {
                    for (i in 0 until n) {
                        val col = cursorCol + i
                        if (col < cols) screen[cursorRow][col].reset()
                    }
                }
            }
            '@' -> { // Insert Characters
                val n = maxOf(params.getOrElse(0) { 1 }, 1)
                insertChars(n)
            }
            'd' -> { // Line Position Absolute
                val n = maxOf(params.getOrElse(0) { 1 }, 1)
                cursorRow = minOf(n - 1, rows - 1)
            }
            'm' -> executeSgr(params) // SGR
            'n' -> { // Device Status Report
                // Respond with cursor position - not implemented (needs write back)
            }
            'r' -> { // Set Scrolling Region
                scrollTop = maxOf((params.getOrElse(0) { 1 }) - 1, 0)
                scrollBottom = minOf((params.getOrElse(1) { rows }) - 1, rows - 1)
                cursorRow = if (originMode) scrollTop else 0
                cursorCol = 0
            }
            's' -> saveCursor()
            'u' -> restoreCursor()
            'h' -> { // Set Mode
                if (isPrivate) setDecMode(params, true)
            }
            'l' -> { // Reset Mode
                if (isPrivate) setDecMode(params, false)
            }
            'c' -> { /* Device Attributes - ignore */ }
            't' -> { /* Window manipulation - ignore */ }
        }
    }

    private fun executeSgr(params: List<Int>) {
        if (params.isEmpty()) {
            resetAttributes()
            return
        }
        var i = 0
        while (i < params.size) {
            when (params[i]) {
                0 -> resetAttributes()
                1 -> currentBold = true
                2 -> currentDim = true
                3 -> currentItalic = true
                4 -> currentUnderline = true
                7 -> currentInverse = true
                22 -> { currentBold = false; currentDim = false }
                23 -> currentItalic = false
                24 -> currentUnderline = false
                27 -> currentInverse = false
                in 30..37 -> currentFg = params[i] - 30
                38 -> {
                    if (i + 1 < params.size) {
                        when (params[i + 1]) {
                            5 -> { // 256 color
                                if (i + 2 < params.size) {
                                    currentFg = 256 + params[i + 2] // offset to distinguish
                                    i += 2
                                }
                            }
                            2 -> { // true color (r;g;b) - store as high value
                                if (i + 4 < params.size) {
                                    val r = params[i + 2]; val g = params[i + 3]; val b = params[i + 4]
                                    currentFg = 0x1000000 or (r shl 16) or (g shl 8) or b
                                    i += 4
                                }
                            }
                        }
                    }
                }
                39 -> currentFg = 7 // default fg
                in 40..47 -> currentBg = params[i] - 40
                48 -> {
                    if (i + 1 < params.size) {
                        when (params[i + 1]) {
                            5 -> {
                                if (i + 2 < params.size) {
                                    currentBg = 256 + params[i + 2]
                                    i += 2
                                }
                            }
                            2 -> {
                                if (i + 4 < params.size) {
                                    val r = params[i + 2]; val g = params[i + 3]; val b = params[i + 4]
                                    currentBg = 0x1000000 or (r shl 16) or (g shl 8) or b
                                    i += 4
                                }
                            }
                        }
                    }
                }
                49 -> currentBg = 0 // default bg
                in 90..97 -> currentFg = params[i] - 90 + 8
                in 100..107 -> currentBg = params[i] - 100 + 8
            }
            i++
        }
    }

    private fun setDecMode(params: List<Int>, enable: Boolean) {
        for (p in params) {
            when (p) {
                1 -> applicationCursorKeys = enable
                7 -> autoWrap = enable
                6 -> originMode = enable
                25 -> showCursor = enable
                47 -> switchScreen(enable)
                1000, 1002, 1003 -> { /* mouse tracking - not implemented */ }
                1047 -> switchScreen(enable)
                1048 -> if (enable) saveCursor() else restoreCursor()
                1049 -> {
                    if (enable) { saveCursor(); switchScreen(true) }
                    else { switchScreen(false); restoreCursor() }
                }
                2004 -> { /* bracketed paste - could implement */ }
            }
        }
    }

    private fun switchScreen(toAlternate: Boolean) {
        if (toAlternate && altScreen == null) {
            altScreen = screen
            altCursorRow = cursorRow
            altCursorCol = cursorCol
            screen = createBuffer(rows, cols)
            cursorRow = 0
            cursorCol = 0
        } else if (!toAlternate && altScreen != null) {
            screen = altScreen!!
            cursorRow = altCursorRow
            cursorCol = altCursorCol
            altScreen = null
        }
    }

    private fun executeOsc() {
        val content = oscBuffer.toString()
        val semicolonIdx = content.indexOf(';')
        if (semicolonIdx > 0) {
            val cmd = content.substring(0, semicolonIdx)
            val arg = content.substring(semicolonIdx + 1)
            when (cmd) {
                "0", "2" -> title = arg // Set window title
                "1" -> { /* Icon name */ }
            }
        }
    }

    private fun parseParams(): List<Int> {
        val raw = paramBuffer.toString().removePrefix("?").removePrefix(">")
        if (raw.isEmpty()) return emptyList()
        return raw.split(';').map { it.toIntOrNull() ?: 0 }
    }

    private fun saveCursor() {
        savedCursorRow = cursorRow
        savedCursorCol = cursorCol
    }

    private fun restoreCursor() {
        cursorRow = savedCursorRow
        cursorCol = savedCursorCol
    }

    private fun resetAttributes() {
        currentFg = 7; currentBg = 0
        currentBold = false; currentItalic = false
        currentUnderline = false; currentInverse = false; currentDim = false
    }

    private fun eraseFromCursor() {
        eraseLineFromCursor()
        for (r in cursorRow + 1 until rows) clearLine(r)
    }

    private fun eraseToCursor() {
        eraseLineToCursor()
        for (r in 0 until cursorRow) clearLine(r)
    }

    private fun eraseScreen() {
        for (r in 0 until rows) clearLine(r)
    }

    private fun eraseLineFromCursor() {
        if (cursorRow < 0 || cursorRow >= rows) return
        for (c in cursorCol.coerceAtLeast(0) until cols) screen[cursorRow][c].reset()
    }

    private fun eraseLineToCursor() {
        if (cursorRow < 0 || cursorRow >= rows) return
        for (c in 0..cursorCol.coerceAtMost(cols - 1)) screen[cursorRow][c].reset()
    }

    private fun insertLine() {
        if (cursorRow < scrollTop || cursorRow > scrollBottom) return
        for (r in scrollBottom downTo cursorRow + 1) {
            for (c in 0 until cols) screen[r][c].copyFrom(screen[r - 1][c])
        }
        clearLine(cursorRow)
    }

    private fun deleteLine() {
        if (cursorRow < scrollTop || cursorRow > scrollBottom) return
        for (r in cursorRow until scrollBottom) {
            for (c in 0 until cols) screen[r][c].copyFrom(screen[r + 1][c])
        }
        clearLine(scrollBottom)
    }

    private fun insertChars(n: Int) {
        if (cursorRow < 0 || cursorRow >= rows) return
        for (c in cols - 1 downTo cursorCol + n) {
            if (c - n >= 0) screen[cursorRow][c].copyFrom(screen[cursorRow][c - n])
        }
        for (c in cursorCol until minOf(cursorCol + n, cols)) {
            screen[cursorRow][c].reset()
        }
    }

    private fun deleteChars(n: Int) {
        if (cursorRow < 0 || cursorRow >= rows) return
        for (c in cursorCol until cols - n) {
            screen[cursorRow][c].copyFrom(screen[cursorRow][c + n])
        }
        for (c in maxOf(cols - n, cursorCol) until cols) {
            screen[cursorRow][c].reset()
        }
    }

    private fun fullReset() {
        resetAttributes()
        cursorRow = 0; cursorCol = 0
        scrollTop = 0; scrollBottom = rows - 1
        showCursor = true; autoWrap = true
        applicationCursorKeys = false; originMode = false
        altScreen = null
        eraseScreen()
    }

    // Color utilities
    companion object {
        val ANSI_COLORS = intArrayOf(
            0xFF000000.toInt(), // 0: Black
            0xFFCC0000.toInt(), // 1: Red
            0xFF4E9A06.toInt(), // 2: Green
            0xFFC4A000.toInt(), // 3: Yellow
            0xFF3465A4.toInt(), // 4: Blue
            0xFF75507B.toInt(), // 5: Magenta
            0xFF06989A.toInt(), // 6: Cyan
            0xFFD3D7CF.toInt(), // 7: White
            0xFF555753.toInt(), // 8: Bright Black
            0xFFEF2929.toInt(), // 9: Bright Red
            0xFF8AE234.toInt(), // 10: Bright Green
            0xFFFCE94F.toInt(), // 11: Bright Yellow
            0xFF729FCF.toInt(), // 12: Bright Blue
            0xFFAD7FA8.toInt(), // 13: Bright Magenta
            0xFF34E2E2.toInt(), // 14: Bright Cyan
            0xFFEEEEEC.toInt(), // 15: Bright White
        )

        // Catppuccin Mocha colors for a nicer look
        val CATPPUCCIN_COLORS = intArrayOf(
            0xFF45475A.toInt(), // 0: Black (Surface1)
            0xFFF38BA8.toInt(), // 1: Red
            0xFFA6E3A1.toInt(), // 2: Green
            0xFFF9E2AF.toInt(), // 3: Yellow
            0xFF89B4FA.toInt(), // 4: Blue
            0xFFF5C2E7.toInt(), // 5: Magenta/Pink
            0xFF94E2D5.toInt(), // 6: Cyan/Teal
            0xFFBAC2DE.toInt(), // 7: White (Subtext1)
            0xFF585B70.toInt(), // 8: Bright Black (Surface2)
            0xFFF38BA8.toInt(), // 9: Bright Red
            0xFFA6E3A1.toInt(), // 10: Bright Green
            0xFFF9E2AF.toInt(), // 11: Bright Yellow
            0xFF89B4FA.toInt(), // 12: Bright Blue
            0xFFF5C2E7.toInt(), // 13: Bright Magenta
            0xFF94E2D5.toInt(), // 14: Bright Cyan
            0xFFA6ADC8.toInt(), // 15: Bright White (Subtext0)
        )

        val DEFAULT_BG_COLOR = 0xFF1E1E2E.toInt() // Catppuccin Base
        val DEFAULT_FG_COLOR = 0xFFCDD6F4.toInt() // Catppuccin Text
        val CURSOR_COLOR = 0xFFF5E0DC.toInt() // Catppuccin Rosewater

        fun resolveColor(colorIndex: Int, isBold: Boolean = false, colors: IntArray = CATPPUCCIN_COLORS): Int {
            return when {
                colorIndex >= 0x1000000 -> colorIndex or (0xFF shl 24).toInt() // true color
                colorIndex >= 256 -> { // 256-color
                    val idx = colorIndex - 256
                    when {
                        idx < 16 -> colors[idx]
                        idx < 232 -> {
                            val i = idx - 16
                            val r = (i / 36) * 51
                            val g = ((i % 36) / 6) * 51
                            val b = (i % 6) * 51
                            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                        }
                        else -> {
                            val gray = 8 + (idx - 232) * 10
                            (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
                        }
                    }
                }
                colorIndex in 0..15 -> {
                    val effectiveIdx = if (isBold && colorIndex < 8) colorIndex + 8 else colorIndex
                    colors[effectiveIdx]
                }
                else -> DEFAULT_FG_COLOR
            }
        }
    }
}
