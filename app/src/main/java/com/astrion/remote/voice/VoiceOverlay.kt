package com.astrion.remote.voice

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

@Composable
fun VoiceOverlay(state: VoiceState, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(18.dp), tonalElevation = 6.dp) {
            Column(
                Modifier.padding(20.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (state) {
                    is VoiceState.Listening -> {
                        Text("🎤", style = MaterialTheme.typography.displaySmall)
                        Spacer(Modifier.height(8.dp))
                        Text("Listening…", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Speak now — stops when you pause",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    is VoiceState.Thinking -> {
                        Text("💭", style = MaterialTheme.typography.displaySmall)
                        Spacer(Modifier.height(8.dp))
                        state.transcript?.let {
                            Text("“$it”", style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(6.dp))
                        }
                        Text("Thinking…", style = MaterialTheme.typography.titleMedium)
                    }
                    is VoiceState.Answer -> {
                        state.transcript?.let {
                            Text(
                                "“$it”",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        Text(
                            state.speech ?: "Done",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    is VoiceState.Error -> {
                        Text("⚠️", style = MaterialTheme.typography.displaySmall)
                        Spacer(Modifier.height(8.dp))
                        Text(state.message, style = MaterialTheme.typography.bodyMedium)
                    }
                    is VoiceState.Idle -> {}
                }
            }
        }
    }
}
