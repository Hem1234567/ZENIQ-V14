package com.astra.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The local model in use. "Auto" hands the choice to llm/ModelRouter (which prefers the model that is already
 * loaded); a model picked here overrides it on every turn. Black and white, like the rest of the app.
 */
@Composable
fun ModelChip(vm: AstraViewModel, loaded: String?) {
    val c = LocalIos.current
    val settings by vm.settings.collectAsState()
    val installed by vm.installed.collectAsState()
    var open by remember { mutableStateOf(false) }
    val name = (loaded ?: "No model").removeSuffix(".gguf")
    Box {
        Row(Modifier.clip(RoundedCornerShape(20.dp)).background(c.fill)
            .pressScale { open = true }.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Memory, contentDescription = null, tint = c.sub, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(5.dp))
            Text(if (settings.autoModel) "Auto \u00b7 $name" else name, fontSize = 12.sp, color = c.sub, maxLines = 1)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = "Choose model", tint = c.sub, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Auto \u00b7 best model for each request", fontSize = 14.sp) },
                leadingIcon = {
                    if (settings.autoModel) Icon(Icons.Filled.Check, contentDescription = null, tint = c.accent, modifier = Modifier.size(16.dp))
                },
                onClick = { vm.setAutoModel(true); open = false })
            installed.forEach { f ->
                DropdownMenuItem(
                    text = { Text(f.name.removeSuffix(".gguf"), fontSize = 14.sp, maxLines = 1) },
                    leadingIcon = {
                        if (!settings.autoModel && f.name == loaded)
                            Icon(Icons.Filled.Check, contentDescription = null, tint = c.accent, modifier = Modifier.size(16.dp))
                    },
                    onClick = { vm.selectModel(f); open = false })
            }
            if (installed.isEmpty()) DropdownMenuItem(
                text = { Text("No models downloaded yet", fontSize = 13.sp) },
                enabled = false, onClick = { })
        }
    }
}
