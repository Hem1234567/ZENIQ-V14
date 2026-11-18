package com.astra.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Small shared pieces for the Automate, SkillForge and Plugins screens (named Hub* so they never clash with the rest of the app). */

@Composable
fun HubTitle(text: String, sub: String = "") {
    val c = LocalIos.current
    Column(Modifier.padding(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 4.dp)) {
        Text(text, fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp)
        if (sub.isNotEmpty()) Text(sub, fontSize = 13.sp, color = c.sub)
    }
}

@Composable
fun HubSeg(items: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val c = LocalIos.current
    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.fill).padding(2.dp)) {
        items.forEachIndexed { i, t ->
            val sel = i == selected
            Box(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (sel) c.card else Color.Transparent)
                .pressScale { onSelect(i) }.padding(vertical = 7.dp), contentAlignment = Alignment.Center) {
                Text(t, fontSize = 13.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal, color = if (sel) c.text else c.sub, maxLines = 1)
            }
        }
    }
}

@Composable
fun HubChips(items: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val c = LocalIos.current
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEachIndexed { i, t ->
            val sel = i == selected
            Box(Modifier.clip(CircleShape).background(if (sel) c.accent else c.fill).pressScale { onSelect(i) }.padding(horizontal = 14.dp, vertical = 7.dp)) {
                Text(t, fontSize = 13.sp, color = if (sel) c.onAccent else c.text, maxLines = 1)
            }
        }
    }
}

@Composable
fun HubCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.padding(horizontal = 16.dp, vertical = 5.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(LocalIos.current.card).padding(14.dp), content = content)
}

@Composable
fun HubBadge(text: String, color: Color) {
    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.16f)).padding(horizontal = 7.dp, vertical = 2.dp)) {
        Text(text, fontSize = 11.sp, color = color, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun HubDot(color: Color) { Box(Modifier.size(9.dp).clip(CircleShape).background(color)) }

@Composable
fun HubButton(text: String, color: Color = LocalIos.current.accent, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) =
    PillButton(text, color, enabled, modifier, onClick)

@Composable
fun HubField(value: String, onChange: (String) -> Unit, label: String, secret: Boolean = false, single: Boolean = true, minLines: Int = 1, modifier: Modifier = Modifier) {
    val c = LocalIos.current
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label, fontSize = 13.sp) },
        singleLine = single, minLines = minLines,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = c.accent, unfocusedBorderColor = c.line, focusedTextColor = c.text, unfocusedTextColor = c.text,
            cursorColor = c.accent, focusedLabelColor = c.accent, unfocusedLabelColor = c.sub
        )
    )
}

@Composable
fun HubSwitch(on: Boolean, onChange: (Boolean) -> Unit) {
    val c = LocalIos.current
    Switch(on, onChange, colors = SwitchDefaults.colors(checkedTrackColor = c.green, checkedThumbColor = c.onAccent))
}

fun hubTime(ts: Long): String = if (ts <= 0L) "" else java.text.SimpleDateFormat("d MMM, h:mm a", java.util.Locale.getDefault()).format(java.util.Date(ts))
