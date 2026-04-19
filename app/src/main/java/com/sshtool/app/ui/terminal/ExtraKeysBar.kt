package com.sshtool.app.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class ExtraKey(
    val label: String,
    val action: ExtraKeyAction
)

sealed class ExtraKeyAction {
    data class Special(val key: SpecialKey) : ExtraKeyAction()
    data class Ctrl(val char: Char) : ExtraKeyAction()
    data class Text(val text: String) : ExtraKeyAction()
}

@Composable
fun ExtraKeysBar(
    onSpecialKey: (SpecialKey) -> Unit,
    onCtrlKey: (Char) -> Unit,
    onTextInput: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var showFKeys by remember { mutableStateOf(false) }
    var ctrlActive by remember { mutableStateOf(false) }

    val mainKeys = listOf(
        ExtraKey("ESC", ExtraKeyAction.Special(SpecialKey.ESCAPE)),
        ExtraKey("TAB", ExtraKeyAction.Special(SpecialKey.TAB)),
        ExtraKey("CTRL", ExtraKeyAction.Text("CTRL_TOGGLE")),
        ExtraKey("ALT", ExtraKeyAction.Text("ALT_TOGGLE")),
        ExtraKey("←", ExtraKeyAction.Special(SpecialKey.LEFT)),
        ExtraKey("→", ExtraKeyAction.Special(SpecialKey.RIGHT)),
        ExtraKey("↑", ExtraKeyAction.Special(SpecialKey.UP)),
        ExtraKey("↓", ExtraKeyAction.Special(SpecialKey.DOWN)),
        ExtraKey("HOME", ExtraKeyAction.Special(SpecialKey.HOME)),
        ExtraKey("END", ExtraKeyAction.Special(SpecialKey.END)),
        ExtraKey("PGUP", ExtraKeyAction.Special(SpecialKey.PAGE_UP)),
        ExtraKey("PGDN", ExtraKeyAction.Special(SpecialKey.PAGE_DOWN)),
        ExtraKey("Fn", ExtraKeyAction.Text("FN_TOGGLE")),
    )

    val fKeys = listOf(
        ExtraKey("F1", ExtraKeyAction.Special(SpecialKey.F1)),
        ExtraKey("F2", ExtraKeyAction.Special(SpecialKey.F2)),
        ExtraKey("F3", ExtraKeyAction.Special(SpecialKey.F3)),
        ExtraKey("F4", ExtraKeyAction.Special(SpecialKey.F4)),
        ExtraKey("F5", ExtraKeyAction.Special(SpecialKey.F5)),
        ExtraKey("F6", ExtraKeyAction.Special(SpecialKey.F6)),
        ExtraKey("F7", ExtraKeyAction.Special(SpecialKey.F7)),
        ExtraKey("F8", ExtraKeyAction.Special(SpecialKey.F8)),
        ExtraKey("F9", ExtraKeyAction.Special(SpecialKey.F9)),
        ExtraKey("F10", ExtraKeyAction.Special(SpecialKey.F10)),
        ExtraKey("F11", ExtraKeyAction.Special(SpecialKey.F11)),
        ExtraKey("F12", ExtraKeyAction.Special(SpecialKey.F12)),
    )

    val ctrlKeys = listOf(
        ExtraKey("C", ExtraKeyAction.Ctrl('C')),
        ExtraKey("D", ExtraKeyAction.Ctrl('D')),
        ExtraKey("Z", ExtraKeyAction.Ctrl('Z')),
        ExtraKey("A", ExtraKeyAction.Ctrl('A')),
        ExtraKey("L", ExtraKeyAction.Ctrl('L')),
        ExtraKey("R", ExtraKeyAction.Ctrl('R')),
        ExtraKey("\\", ExtraKeyAction.Ctrl('\\')),
        ExtraKey("/", ExtraKeyAction.Text("/")),
        ExtraKey("|", ExtraKeyAction.Text("|")),
        ExtraKey("~", ExtraKeyAction.Text("~")),
        ExtraKey("-", ExtraKeyAction.Text("-")),
        ExtraKey("_", ExtraKeyAction.Text("_")),
    )

    val displayKeys = when {
        ctrlActive -> ctrlKeys
        showFKeys -> fKeys
        else -> mainKeys
    }

    Column(modifier = modifier.background(Color(0xFF181825))) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            displayKeys.forEach { key ->
                ExtraKeyButton(
                    label = key.label,
                    isActive = (key.label == "CTRL" && ctrlActive) ||
                            (key.label == "Fn" && showFKeys),
                    onClick = {
                        when (key.action) {
                            is ExtraKeyAction.Special -> {
                                onSpecialKey(key.action.key)
                                ctrlActive = false
                            }
                            is ExtraKeyAction.Ctrl -> {
                                onCtrlKey(key.action.char)
                                ctrlActive = false
                            }
                            is ExtraKeyAction.Text -> {
                                when (key.action.text) {
                                    "CTRL_TOGGLE" -> {
                                        ctrlActive = !ctrlActive
                                        showFKeys = false
                                    }
                                    "FN_TOGGLE" -> {
                                        showFKeys = !showFKeys
                                        ctrlActive = false
                                    }
                                    "ALT_TOGGLE" -> { /* Alt mode - future */ }
                                    else -> {
                                        onTextInput(key.action.text)
                                        ctrlActive = false
                                    }
                                }
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun ExtraKeyButton(
    label: String,
    isActive: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .height(36.dp)
            .widthIn(min = 40.dp)
            .background(
                if (isActive) Color(0xFF585B70) else Color(0xFF313244),
                shape = MaterialTheme.shapes.extraSmall
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (isActive) Color(0xFFA6E3A1) else Color(0xFFBAC2DE),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}
