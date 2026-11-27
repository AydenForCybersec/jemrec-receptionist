package com.jemcik.jemrec.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jemcik.jemrec.capture.AiAudio
import com.jemcik.jemrec.capture.AiLive

/**
 * THE LIVE CALL: what the assistant is doing, what was said, the caller's
 * voice while it is happening, and the two ways out - take the call, or hang
 * it up. Fed by the call service's monitor socket, so it works with the app
 * in front of the call or opened from its notification.
 *
 * Nothing here is recorded. The transcript is the call's record, and it is
 * gone when the app's process is.
 */
@Composable
fun AiMonitorScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val live by AiLive.state.collectAsStateWithLifecycle()
    val listening by AiAudio.enabled.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    // The saved choice, for a screen opened before this process's first AI
    // call: the service reads it too, but the switch here must not say off.
    LaunchedEffect(Unit) { AiAudio.load(context) }

    Column(modifier) {
        SettingsGroup("This call") {
            row {
                SettingsRow(
                    title = when {
                        live.up -> phaseTitle(live.phase)
                        live.transcript.isNotEmpty() -> "Last AI call"
                        else -> "No AI call"
                    },
                    summary = when {
                        live.up -> "Your microphone is muted. The caller hears the assistant only."
                        live.transcript.isNotEmpty() -> "The call is over. Its transcript is below."
                        else -> "When the assistant answers a call, this screen fills in."
                    },
                    icon = Icons.Filled.Call,
                )
            }
        }

        if (live.up) {
            SettingsGroup("Listen") {
                row {
                    SwitchRow(
                        title = "Hear the caller",
                        checked = listening,
                        onCheckedChange = { on ->
                            haptics.toggle(on)
                            AiAudio.set(context, on)
                        },
                        summary = if (listening) {
                            "Live, through this phone's speaker or earpiece"
                        } else {
                            "Off - the caller is not played on this phone"
                        },
                        icon = Icons.Filled.Call,
                    )
                }
            }
        }

        SettingsGroup(
            "Transcript",
            note = if (live.transcript.isEmpty()) {
                null
            } else {
                "What the caller and the assistant said, as they said it. Nothing is recorded."
            },
        ) {
            if (live.transcript.isEmpty()) {
                row {
                    SettingsRow(
                        title = "Nothing yet",
                        summary = if (live.up) "Waiting for the caller." else "No AI call has been answered yet.",
                        icon = Icons.Filled.Call,
                    )
                }
            } else {
                live.transcript.forEachIndexed { index, line ->
                    row(key = index) {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(
                                if (line.who == "caller") "Caller" else "Assistant",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                line.text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }

        if (live.up) {
            ButtonPair(listOf("Talk to them", "Hang up"), Modifier.padding(top = 8.dp)) { i, buttonModifier ->
                FilledTonalButton(
                    onClick = {
                        haptics.tap()
                        vm.handOverAi(talk = i == 0)
                    },
                    modifier = buttonModifier,
                ) { ButtonLabel(if (i == 0) "Talk to them" else "Hang up") }
            }
        }
    }
}

/** The state in a few words, on the call's own row. */
private fun phaseTitle(phase: String): String = when (phase) {
    "starting" -> "Getting ready"
    "intro" -> "Saying hello"
    "listening" -> "Listening"
    "thinking" -> "Thinking"
    "speaking" -> "Speaking"
    "handoff" -> "The caller asked for you"
    "ended" -> "Call ended"
    else -> "Assistant answering"
}
