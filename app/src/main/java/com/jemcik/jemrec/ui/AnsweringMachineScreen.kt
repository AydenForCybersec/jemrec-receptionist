package com.jemcik.jemrec.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jemcik.jemrec.answering.AnswerDiagnostics
import com.jemcik.jemrec.answering.AnsweringMachine
import com.jemcik.jemrec.answering.EmergencyPause
import com.jemcik.jemrec.answering.Greeting
import com.jemcik.jemrec.capture.AiLive
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Which of the answering machine's dialogs is open. Saved by name. */
private enum class AnswerDialog { DELAY, DND, LONGEST, NO_LIMIT, SIM, TEST, CARRIER, RESUME }

/**
 * THE ANSWERING MACHINE: the AI assistant answers the phone calls you do not
 * take, with your microphone muted, and no call is recorded. It says it is
 * automated, asks who is calling, and replies from the on-phone model. Off
 * until switched on, and it can be switched on once the no-call checks pass.
 * The test call underneath is how a phone goes from "possible" to "works": it
 * plays a short tone and beep on the route the assistant's voice takes.
 */
@Composable
fun AnsweringMachineScreen(state: UiState, vm: MainViewModel, modifier: Modifier = Modifier) {
    val a by vm.answer.state.collectAsState()
    // The assistant's call, while it is up: this screen's way into the live
    // transcript and the two ways out of the call.
    val live by AiLive.state.collectAsStateWithLifecycle()
    // A take holding the microphone, this screen's or another window's: the
    // greeting takes no tap - a preview would go into the take - and Record
    // and Delete wait, as for this screen's own take.
    val holder by Greeting.holder.collectAsState()
    val listening = a.takeListening || holder != null
    // A take being made a greeting of, here or in another window: Record
    // would overwrite the file being read, and the saving undo a Delete.
    val making by Greeting.making.collectAsState()
    val processing = a.processingTake || making != null
    // A take made a greeting of past its screen - closed meanwhile - is
    // shown once saved: the screen opened since said "No greeting yet".
    // How a take ended, kept for the screen in front - this one, if it is
    // (readTakeEnding): a window behind another leaves it for that one.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(making) {
        if (making == null) {
            vm.answer.reload()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) vm.answer.readTakeEnding()
        }
    }
    val haptics = LocalHapticFeedback.current
    var dialog by rememberSaveable { mutableStateOf<AnswerDialog?>(null) }

    LaunchedEffect(Unit) { vm.answeringMachineOpened() }
    // Back at the front: where a test kept when its watch ended unanswered,
    // or still under way, stands; and how a take made past its screen ended.
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        vm.answer.readPendingTest()
        vm.answer.readTakeEnding()
    }
    val now = LocalNow.current

    when (dialog) {
        AnswerDialog.DELAY -> ChoiceDialog(
            title = "Answer after",
            intro = "How long it rings before JemRec answers. Your carrier's own voicemail, " +
                "if you have it, must wait longer.",
            choices = AnsweringMachine.DELAYS,
            selected = a.delay,
            label = { "$it seconds" },
            onChoose = vm.answer::setDelay,
            onDismiss = { dialog = null },
        )
        AnswerDialog.DND -> ChoiceDialog(
            title = "With Do Not Disturb on",
            intro = "For callers Do Not Disturb would silence. Callers it lets through ring as usual.",
            choices = listOf(true, false),
            selected = a.dndShort,
            label = { if (it) "After 2 rings (about ${AnsweringMachine.DND_DELAY} seconds)" else "The same delay" },
            onChoose = vm.answer::setDndShort,
            onDismiss = { dialog = null },
        )
        AnswerDialog.LONGEST -> ChoiceDialog(
            title = "Longest call",
            intro = "How long the assistant may keep the call before it closes and hangs up. " +
                "No limit runs until the caller hangs up, or falls silent, but the assistant " +
                "still stops at 2 minutes.",
            choices = AnsweringMachine.LONGEST,
            selected = a.longest,
            label = ::longestLabel,
            // No limit is asked about first, in a dialog of its own.
            onChoose = {
                if (it == AnsweringMachine.NO_LIMIT) dialog = AnswerDialog.NO_LIMIT else vm.answer.setLongest(it)
            },
            onDismiss = { if (dialog == AnswerDialog.LONGEST) dialog = null },
        )
        AnswerDialog.NO_LIMIT -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("No limit?") },
            text = {
                Text(
                    "The call runs until the caller hangs up, or until they have been quiet " +
                        "for 15 seconds. The assistant still stops at 2 minutes: that is its hard " +
                        "cap. Until then your phone is in " +
                        "that call: you cannot make a call without ending it, and other calls " +
                        "wait. A caller somewhere noisy may never count as silent, and then it " +
                        "lasts until they hang up.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    haptics.confirm()
                    vm.answer.setLongest(AnsweringMachine.NO_LIMIT)
                    dialog = null
                }) { Text("No limit") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Cancel") } },
        )
        AnswerDialog.SIM -> ChoiceDialog(
            title = "Answer on",
            choices = a.sims.map { it.first } + AnsweringMachine.SIM_BOTH,
            selected = when (a.sim) {
                AnsweringMachine.SIM_DEFAULT -> a.defaultSim ?: AnsweringMachine.SIM_BOTH
                else -> a.sim
            },
            label = { id -> if (id == AnsweringMachine.SIM_BOTH) "Both SIMs" else simLabel(a, id) },
            onChoose = vm.answer::setSim,
            onDismiss = { dialog = null },
        )
        AnswerDialog.TEST -> TestDialog(
            pausedUntil = a.pausedUntil,
            start = { incoming, number -> dialog = null; vm.answer.startTest(incoming, number) },
            dismiss = { dialog = null },
        )
        AnswerDialog.CARRIER -> CarrierDialog(a, vm, dismiss = { dialog = null })
        AnswerDialog.RESUME -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("Answer calls again?") },
            text = {
                Text(
                                            "You called an emergency number. If they call you back while you cannot " +
                        "pick up, the AI assistant would answer them, instead of letting your " +
                        "phone ring until you can." +
                        (a.pausedUntil?.let { " The pause ends by itself on ${pauseEnd(it)}." } ?: ""),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    haptics.confirm()
                    vm.answer.resumeAfterEmergency()
                    dialog = null
                }) { Text("Answer again") }
            },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("Keep paused") } },
        )
        null -> Unit
    }

    a.askHeard?.let { type ->
        AlertDialog(
            onDismissRequest = vm.answer::dismissHeard,
            title = { Text("Did the caller hear the test?") },
            text = { Text("The tone, your greeting if you recorded one, and the beep. No sound from around this phone.") },
            confirmButton = { TextButton(onClick = { haptics.confirm(); vm.answer.heard(type, true) }) { Text("Yes") } },
            dismissButton = { TextButton(onClick = { vm.answer.heard(type, false) }) { Text("No") } },
        )
    }

    Column(modifier) {
        SettingsGroup {
            row {
                val blocked = a.blocked
                val paused = a.paused
                SwitchRow(
                    title = "Answering machine",
                    summary = when {
                        blocked != null && !a.enabled -> blocked
                        paused != null -> "Paused after an emergency call, until ${pauseEnd(paused)}"
                        a.enabled -> "The AI assistant answers the calls you don't take, after ${a.delay} seconds"
                        else -> "Off"
                    },
                    summaryColor = if (blocked != null && a.stageOne?.possible == false || paused != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    // ANS, as on the Settings row that leads here.
                    mark = headerMark(Marked.ANSWERING, marks(state, a.enabled), recorderStatus(state)),
                    checked = a.enabled,
                    enabled = a.enabled || blocked == null,
                    onCheckedChange = vm.answer::setEnabled,
                )
            }
            // A day after a call to an emergency number it stands down, so a
            // callback rings on (EmergencyPause). Ending that early is the
            // user's to choose, warned.
            if (a.paused != null) {
                row {
                    SettingsRow(
                        title = "Answer again now",
                        summary = "It stands down for a day after you call an emergency number",
                        icon = Icons.Filled.Warning,
                        onClick = { dialog = AnswerDialog.RESUME },
                    )
                }
            }
        }

        if (live.up) {
            SettingsGroup("This call") {
                row {
                    SettingsRow(
                        title = "AI call in progress",
                        summary = "Tap for the live transcript, to hear the caller, or to take the call",
                        icon = Icons.Filled.Call,
                        onClick = { vm.showScreen(Screen.AI_MONITOR) },
                    )
                }
            }
        }

        SettingsGroup(
            "When it answers",
            note = "It answers only the callers your rules keep: Whose calls to keep, in " +
                "Settings, applies here too.",
        ) {
            row {
                SettingsRow(
                    title = "Answer after",
                    summary = "${a.delay} seconds" + (a.carrierSeconds?.takeIf { it <= a.delay }
                        ?.let { " - your carrier's voicemail answers first, at $it" } ?: ""),
                    summaryColor = if (a.carrierSeconds?.let { it <= a.delay } == true) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    icon = SettingsIcons.Timer,
                    onClick = { dialog = AnswerDialog.DELAY },
                )
            }
            row {
                SettingsRow(
                    title = "With Do Not Disturb on",
                    summary = if (a.dndShort) "After 2 rings" else "The same delay",
                    icon = Icons.Filled.Notifications,
                    onClick = { dialog = AnswerDialog.DND },
                )
            }
            if (a.sims.size >= 2) {
                row {
                    SettingsRow(
                        title = "Answer on",
                        summary = when (a.sim) {
                            AnsweringMachine.SIM_BOTH -> "Both SIMs"
                            AnsweringMachine.SIM_DEFAULT -> a.defaultSim?.let { simLabel(a, it) } ?: "Both SIMs"
                            else -> simLabel(a, a.sim)
                        },
                        icon = Icons.Filled.Call,
                        onClick = { dialog = AnswerDialog.SIM },
                    )
                }
            }
            row {
                SettingsRow(
                    title = "Longest call",
                    summary = if (a.longest == AnsweringMachine.NO_LIMIT) {
                        "No limit - the assistant still stops at 2 minutes"
                    } else {
                        "${seconds(a.longest)}, then the assistant hangs up"
                    },
                    icon = SettingsIcons.Timer,
                    onClick = { dialog = AnswerDialog.LONGEST },
                )
            }
        }

        SettingsGroup(
            "Greeting for the test call",
            note = "Optional. The AI assistant speaks its own first line; the test call uses " +
                "this recording when you make one, and a beep when you do not.",
        ) {
            row {
                SettingsRow(
                    title = when {
                        a.recordingTake -> "Recording your greeting"
                        a.greetingSeconds != null -> "Your greeting"
                        else -> "No greeting yet"
                    },
                    summary = when {
                        a.recordingTake -> "Speak now. Tap Stop when you are done (${com.jemcik.jemrec.answering.Greeting.MAX_SECONDS} seconds at most)."
                        // Asked to start, or to stop, and not yet answered:
                        // no "Tap to play it" on a row that takes no tap.
                        listening -> "Waiting for the recorder..."
                        a.greetingProblem != null -> a.greetingProblem
                        a.greetingSeconds != null ->
                            "${seconds((a.greetingSeconds ?: 0.0).toInt().coerceAtLeast(1))}. " +
                                if (a.playing) "Playing - tap to stop" else "Tap to play it"
                        else -> "Record a voice for the test call, or leave it out"
                    },
                    // Red only for the problem itself: "Waiting for the
                    // recorder..." over an earlier take's problem showed red.
                    summaryColor = if (a.greetingProblem != null && !a.recordingTake && !listening) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    icon = if (a.recordingTake) SettingsIcons.Mic else Icons.Filled.PlayArrow,
                    // Not while the recorder's microphone may be open for a
                    // take: the greeting would go into it.
                    onClick = if (a.greetingSeconds != null && !listening) vm.answer::togglePreview else null,
                )
            }
        }
        val record = if (a.greetingSeconds == null) "Record" else "Record again"
        val labels = when {
            a.recordingTake -> listOf("Stop")
            a.greetingSeconds != null -> listOf(record, "Delete")
            else -> listOf(record)
        }
        ButtonPair(labels, Modifier.padding(top = 8.dp)) { i, modifier ->
            when (labels[i]) {
                "Stop" -> FilledTonalButton(
                    onClick = { haptics.tap(); vm.answer.stopTake() },
                    modifier = modifier,
                ) { ButtonLabel("Stop") }
                // Not while a test call is being armed, armed or under way: the
                // test plays this greeting. With it deleted the recorder did
                // not answer an incoming test's caller - the screen waited on,
                // and said no call came - and an outgoing test measured
                // nothing played, and was put down as not working.
                "Delete" -> FilledTonalButton(
                    onClick = { haptics.tap(); vm.answer.deleteGreeting() },
                    enabled = a.test == null && !processing && !listening,
                    modifier = modifier,
                ) { ButtonLabel("Delete") }
                else -> FilledTonalButton(
                    onClick = { haptics.tap(); vm.answer.startTake() },
                    // Not while the last take is made a greeting of: tapped
                    // then, it did nothing, with no sign.
                    enabled = state.daemonRunning && !processing && !listening,
                    modifier = modifier,
                ) { ButtonLabel(record) }
            }
        }
        if (a.recordingTake) TakeTimer(a.takeStartedAt)

        SettingsGroup("Test on this phone") {
            row {
                val stage = a.stageOne
                SettingsRow(
                    title = "Checks",
                    summary = when {
                        a.checking -> "Checking..."
                        stage == null -> "Not run yet - tap to run"
                        stage.possible -> "Passed: this phone can put a voice into a call. A test call tells for sure."
                        else -> stage.problem
                    },
                    summaryColor = if (stage?.possible == false) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    icon = Icons.Filled.Build,
                    onClick = vm.answer::runChecks,
                    enabled = !a.checking && state.daemonRunning,
                )
            }
            row {
                SettingsRow(
                    title = "Test call",
                    summary = when (a.test) {
                        "arming" -> if (a.testIncoming) "Getting the recorder ready..." else "Calling..."
                        "armed" -> when {
                            // Read late, its window over: not "until" a time
                            // gone by.
                            a.testUntil < now() -> "Asking the recorder how it went..."
                            a.testIncoming -> "Waiting for someone to call, until ${timeOfDay(a.testUntil)}"
                            else -> "Calling..."
                        }
                        "running" -> "Testing - the test sound is playing to the call"
                        else -> testedSummary(a)
                    },
                    icon = Icons.Filled.Call,
                    onClick = if (a.test == null) ({ dialog = AnswerDialog.TEST }) else null,
                    enabled = a.stageOne?.possible == true && state.daemonRunning,
                )
            }
            row {
                SettingsRow(
                    title = "Carrier voicemail",
                    summary = when {
                        a.checkingCarrier -> "Asking your carrier... this takes a few seconds"
                        a.carrier == null -> "Tap to ask your carrier when its voicemail answers"
                        a.carrier?.startsWith("off") == true -> "Off: nothing answers before JemRec"
                        a.carrierSeconds != null -> "Answers unanswered calls after ${a.carrierSeconds} seconds"
                        else -> "Your carrier did not say"
                    },
                    icon = SettingsIcons.Voicemail,
                    onClick = { dialog = AnswerDialog.CARRIER },
                    enabled = state.daemonRunning,
                )
            }
            row {
                SettingsRow(
                    title = "Copy a report",
                    summary = "This phone and what the tests found, to paste into a report on GitHub",
                    icon = Icons.Filled.Share,
                    onClick = { haptics.tap(); vm.answer.copyReport() },
                )
            }
        }

        SettingsFooter(
            "When it answers, your microphone is muted and the AI assistant says it is " +
                "automated, asks who is calling and why, and replies. The call is not recorded: " +
                "its transcript is in the notification and on the live call screen. You can take " +
                "the call at any time with Talk to them there.",
            "It answers phone calls only - not WhatsApp, Telegram, Viber or Google Meet " +
                "calls - and never while you are on another call, a messenger call or in a " +
                "meeting, or roaming, or " +
                "for a day after you call an emergency number. If JemRec cannot tell who is " +
                "calling, it lets the phone ring.",
            "The assistant runs in Termux on this phone, so nothing leaves it. Experimental: " +
                "tested on two phones, an Honor on 2G calls and a Galaxy S23 over Wi-Fi Calling. " +
                "The test call here checks the route the assistant's voice takes on yours.",
        )
    }
}

@Composable
private fun TakeTimer(since: Long) {
    // The app's clock, which the screen tests fix.
    val clock = LocalNow.current
    var now by androidx.compose.runtime.remember { mutableLongStateOf(clock()) }
    LaunchedEffect(since) {
        while (true) {
            now = clock()
            delay(250)
        }
    }
    Text(
        takeSeconds(now - since),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(16.dp),
    )
}

@Composable
private fun TestDialog(pausedUntil: Long?, start: (Boolean, String) -> Unit, dismiss: () -> Unit) {
    val context = LocalContext.current
    var number by rememberSaveable { mutableStateOf("") }
    // Sent as a USSD code, not dialled: no call would come of it, and the
    // next call the user made would be taken as the test.
    val code = number.endsWith("#")
    // Never dialled for a test: the call it made would be an emergency call.
    val emergency = remember(number) { EmergencyPause.isEmergencyNumber(context, number) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Test call") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (pausedUntil == null) {
                        "Best: someone calls you. The next call in three minutes is answered with a " +
                            "short tone, your greeting if you recorded one, and a beep, whoever is " +
                            "calling - then you are asked whether they heard it."
                    } else {
                        // Whoever calls in its three minutes is answered: in
                        // the day after an emergency call, that may be them.
                        "Someone calling you is a test again from ${pauseEnd(pausedUntil)}, a day " +
                            "after your emergency call: until then, the caller may be them calling back."
                    },
                )
                Text(
                    "Or JemRec calls a number that answers by itself, such as your carrier's " +
                        "information line, and measures what it can.",
                )
                OutlinedTextField(
                    value = number,
                    onValueChange = { v -> number = v.filter { it.isDigit() || it in "+*#" }.take(32) },
                    label = { Text("Number to call") },
                    singleLine = true,
                    isError = code || emergency,
                    supportingText = when {
                        code -> {
                            { Text("A code ending in # is not a call") }
                        }
                        emergency -> {
                            { Text("Never an emergency number") }
                        }
                        else -> null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { start(true, "") }, enabled = pausedUntil == null) { Text("Someone calls me") }
        },
        dismissButton = {
            TextButton(onClick = { start(false, number) }, enabled = number.length >= 3 && !code && !emergency) {
                Text("Call the number")
            }
        },
    )
}

@Composable
private fun CarrierDialog(a: AnswerUi, vm: MainViewModel, dismiss: () -> Unit) {
    val longer = a.carrierSeconds?.let { it < 30 } == true
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Carrier voicemail") },
        text = {
            Text(
                "Your carrier's own voicemail takes calls nobody answers, after its own " +
                    "timer. JemRec has to answer first. JemRec can ask your carrier for the " +
                    "timer" + if (longer) {
                        ", and make it wait the longest it allows, 30 seconds - your carrier's " +
                            "voicemail then still takes calls JemRec does not. It is your carrier's " +
                            "setting, so it stays if JemRec goes."
                    } else {
                        "."
                    },
            )
        },
        confirmButton = {
            if (longer) {
                TextButton(onClick = { dismiss(); vm.answer.carrierWaitLonger() }) { Text("Wait 30 seconds") }
            } else {
                TextButton(onClick = { dismiss(); vm.answer.checkCarrier() }) { Text("Ask the carrier") }
            }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Close") } },
    )
}

private fun simLabel(a: AnswerUi, id: String): String =
    a.sims.firstOrNull { it.first == id }?.second ?: "SIM"

private fun seconds(s: Int): String = when {
    s < 60 -> "$s seconds"
    s == 60 -> "1 minute"
    s % 60 == 0 -> "${s / 60} minutes"
    else -> "$s seconds"
}

private fun longestLabel(s: Int): String =
    if (s == AnsweringMachine.NO_LIMIT) "No limit (2 minute cap)" else seconds(s)

/** How long the take has run, in whole seconds. */
internal fun takeSeconds(elapsedMs: Long): String = "%d s".format(Locale.US, (elapsedMs / 1000).coerceAtLeast(0))

/** A time of day, as the screen writes it: in Latin digits in any
 *  language, as the rest of the app's times are (clock) - the phone's own
 *  made Arabic-Indic digits in Arabic or Persian, which the looks' fonts do
 *  not have. */
internal fun timeOfDay(millis: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(millis))

/** When a pause ends, with its day: a pause is a day long, so "14:30" alone
 *  would read as today. The month in English, as the words around it are,
 *  and Latin digits (timeOfDay). */
internal fun pauseEnd(millis: Long): String =
    SimpleDateFormat("d MMM 'at' HH:mm", Locale.US).format(Date(millis))

/** The Test call row with no test under way: how the tests came out, and
 *  why an update cleared any (AnswerDiagnostics.Cleared), until a test is
 *  filed again. Tested in AnswerSummaryTest. */
internal fun testedSummary(a: AnswerUi): String {
    val results = AnswerDiagnostics.summary(a.results)
    val why = when (a.cleared) {
        AnswerDiagnostics.Cleared.VOLTE ->
            "Your VoLTE test was cleared: before 0.31 a test over Wi-Fi Calling could be filed as VoLTE. Test again"
        AnswerDiagnostics.Cleared.ROUTES ->
            "Your tests were cleared: since 0.31 the greeting's way to the caller follows the call. Test again"
        null -> null
    }
    return when {
        why == null -> results ?: "Not tested on this phone yet"
        results == null -> why
        else -> "$results. $why"
    }
}

/** The answering machine in a few words, for its row in Settings. Tested in
 *  AnswerSummaryTest. */
internal fun answerSummary(a: AnswerUi): String = when {
    a.paused != null -> "Paused after an emergency call"
    a.enabled -> "After ${a.delay} seconds" + when {
        a.cleared != null -> " - test again after the update"
        a.results.isEmpty() -> " - not tested on this phone yet"
        else -> ""
    }
    a.stageOne?.possible == false -> "Not available on this phone"
    // Why it is off, where the user looks: Android's settings guide, a
    // dependent setting with "a brief explanation of why" it is unavailable.
    !a.callTracking -> "Off - needs exact call tracking"
    else -> "Off"
}

/** The icon the row and the list share. */
internal val messageIcon = SettingsIcons.Voicemail
