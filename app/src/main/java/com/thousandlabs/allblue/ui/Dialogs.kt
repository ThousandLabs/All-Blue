package com.thousandlabs.allblue.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation

/** Petite fenêtre avec un champ de saisie. */
@Composable
fun InputDialog(
    title: String,
    message: String? = null,
    initial: String = "",
    label: String = "",
    confirm: String = "Valider",
    password: Boolean = false,
    singleLine: Boolean = true,
    number: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (message != null) Text(message, color = TextDim)
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = if (label.isNotEmpty()) ({ Text(label) }) else null,
                    singleLine = singleLine,
                    minLines = if (singleLine) 1 else 3,
                    visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = when {
                            password -> KeyboardType.Password
                            number -> KeyboardType.Number
                            else -> KeyboardType.Text
                        }
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

@Composable
fun ConfirmDialog(title: String, message: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } }
    )
}

/** Lit une liste de pages comme « 1-3, 5, 8-10 » (numérotées à partir de 1) → index à partir de 0. */
fun parsePages(input: String, count: Int): List<Int> {
    val out = LinkedHashSet<Int>()
    for (part in input.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }) {
        val bounds = part.split('-').mapNotNull { it.trim().toIntOrNull() }
        when (bounds.size) {
            1 -> bounds[0].takeIf { it in 1..count }?.let { out.add(it - 1) }
            2 -> {
                val a = bounds[0].coerceIn(1, count)
                val b = bounds[1].coerceIn(1, count)
                (if (a <= b) a..b else b..a).forEach { out.add(it - 1) }
            }
        }
    }
    return out.toList()
}
