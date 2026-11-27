package com.jemcik.jemrec.capture

import com.jemcik.jemrec.capture.CallNotice.Notice
import com.jemcik.jemrec.capture.CallNotice.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** What the call service's notification says, in its order of precedence. */
class CallNoticeTest {

    private val idle = State(
        callUp = false, recording = false, notRecording = false, messageTicket = null,
        testing = false, checkingRecorder = false, warningOnly = false,
    )

    private val call = Notice("Call in progress", "JemRec is handling this call", false, null)
    private val recording = Notice("Recording call", "Saving this call", false, null)
    private val notRecording = Notice("Not recording this call", "Tap to see why", false, null)
    private val message = Notice("Taking a message", "Your greeting is playing; your microphone is muted", false, "t")
    private val test = Notice("Testing the answering machine", "The test sound is playing to the caller", false, "t")
    private val cable = Notice("Checking the recorder", "A USB cable went in or came out", true, null)
    private val warning = Notice("Checking JemRec", "Making sure it keeps running", true, null)
    private val assistant = Notice(
        "Assistant answering",
        "The assistant is handling this call; your microphone is muted",
        false,
        null,
    )

    @Test fun eachOnItsOwn() {
        assertEquals(call, CallNotice.of(idle))
        assertEquals(call, CallNotice.of(idle.copy(callUp = true)))
        assertEquals(recording, CallNotice.of(idle.copy(recording = true)))
        assertEquals(notRecording, CallNotice.of(idle.copy(notRecording = true)))
        assertEquals(message, CallNotice.of(idle.copy(messageTicket = "t")))
        assertEquals(test, CallNotice.of(idle.copy(messageTicket = "t", testing = true)))
        assertEquals(cable, CallNotice.of(idle.copy(checkingRecorder = true)))
        assertEquals(warning, CallNotice.of(idle.copy(warningOnly = true)))
        assertEquals(assistant, CallNotice.of(idle.copy(aiUp = true)))
        // Testing without a message being taken says nothing of it.
        assertEquals(call, CallNotice.of(idle.copy(testing = true)))
    }

    @Test fun theAssistantSaysWhatItIsDoingAndWhatTheCallerSaid() {
        val listening = CallNotice.of(
            idle.copy(
                aiUp = true,
                aiWords = "Listening to the caller; your microphone is muted",
                aiLast = "This is Sam about the fence.",
            ),
        )
        assertEquals("Assistant answering", listening.title)
        assertEquals("Listening to the caller; your microphone is muted", listening.text)
        assertEquals("This is Sam about the fence.", listening.subText)
        // No monitor yet: the plain line, and nothing of the caller's.
        assertEquals(assistant, CallNotice.of(idle.copy(aiUp = true, aiLast = "kept")))
        assertNull(CallNotice.of(idle.copy(aiUp = false, aiLast = "gone")).subText)
    }

    @Test fun theAssistantOutranksACallWithNoRecording() {
        assertEquals(assistant, CallNotice.of(idle.copy(callUp = true, aiUp = true)))
        assertEquals(assistant, CallNotice.of(idle.copy(notRecording = true, aiUp = true)))
        // A message's own notice keeps its buttons over it.
        assertEquals(message, CallNotice.of(idle.copy(messageTicket = "t", aiUp = true)))
    }

    @Test fun aNoticeReadsOutAsItIs() {
        val notice = CallNotice.of(idle.copy(messageTicket = "t"))
        assertEquals("Taking a message", notice.title)
        assertEquals("Your greeting is playing; your microphone is muted", notice.text)
        assertEquals(false, notice.deferred)
        assertEquals("t", notice.buttonTicket)
        assertEquals(true, CallNotice.of(idle.copy(checkingRecorder = true)).deferred)
    }

    @Test fun aRecordingStoppedMidCallSaysSoAndWhetherItIsTriedAgain() {
        val trying = CallNotice.of(idle.copy(callUp = true, recording = true, stopped = CallNotice.Stopped.TRYING))
        assertEquals("Recording stopped", trying.title)
        assertEquals("Trying again - this part of the call is not recorded", trying.text)
        val forGood = CallNotice.of(idle.copy(callUp = true, recording = true, stopped = CallNotice.Stopped.FOR_GOOD))
        assertEquals("Recording stopped", forGood.title)
        assertEquals("The rest of this call is not recorded", forGood.text)
        assertFalse(forGood.deferred)
        // A message's notice keeps its buttons over it: the machine hangs up.
        assertEquals(message, CallNotice.of(idle.copy(messageTicket = "t", recording = true, stopped = CallNotice.Stopped.TRYING)))
        // Not recording at all, nothing stopped.
        assertEquals(call, CallNotice.of(idle.copy(callUp = true, stopped = CallNotice.Stopped.TRYING)))
    }

    @Test fun aMessageOutranksARecordingWhichOutranksNotRecording() {
        assertEquals(message, CallNotice.of(idle.copy(messageTicket = "t", recording = true, notRecording = true)))
        assertEquals(recording, CallNotice.of(idle.copy(recording = true, notRecording = true)))
    }

    @Test fun aCallNotRecordedInAskFirstSaysSoAndOffersRecord() {
        val asking = idle.copy(callUp = true, recordTicket = "r")
        assertEquals(
            Notice("Not recording this call", "Tap Record to record it from now on", false, null, "r"),
            CallNotice.of(asking),
        )
        // Recorded, or a message taken: nothing to offer, whatever ticket is live.
        assertEquals(recording, CallNotice.of(asking.copy(recording = true)))
        assertEquals(message, CallNotice.of(asking.copy(messageTicket = "t")))
        // No call up, a ticket left behind: none offered.
        assertEquals(call, CallNotice.of(idle.copy(recordTicket = "r")))
        assertEquals(notRecording, CallNotice.of(idle.copy(recordTicket = "r", notRecording = true)))
        // The recorder lost with the call not recorded: still not recorded.
        assertEquals("r", CallNotice.of(asking.copy(stopped = CallNotice.Stopped.TRYING)).recordTicket)
    }

    @Test fun theChecksAreTheirOwnOnlyWithNothingElseUp() {
        val ups = listOf(idle.copy(callUp = true), idle.copy(recording = true), idle.copy(messageTicket = "t"))
        ups.forEach { up ->
            assertEquals(CallNotice.of(up), CallNotice.of(up.copy(checkingRecorder = true)))
            assertEquals(CallNotice.of(up), CallNotice.of(up.copy(warningOnly = true)))
        }
        // A cable watch's notice comes before the warning's.
        assertEquals(cable, CallNotice.of(idle.copy(checkingRecorder = true, warningOnly = true)))
        // And both before a failed save's "Not recording this call".
        assertEquals(warning, CallNotice.of(idle.copy(warningOnly = true, notRecording = true)))
        assertEquals(cable, CallNotice.of(idle.copy(checkingRecorder = true, notRecording = true)))
    }
}
