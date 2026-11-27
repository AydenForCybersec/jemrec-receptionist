package com.jemcik.jemrec.capture

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The live call's state, from the recorder's STATE, TRANSCRIPT and EVENT lines. */
class AiLiveTest {

    @After fun forget() = AiLive.forget()

    @Test fun aCallBeginsEmpty() {
        AiLive.begin()
        val state = AiLive.state.value
        assertTrue(state.up)
        assertEquals("starting", state.phase)
        assertTrue(state.transcript.isEmpty())
    }

    @Test fun theTranscriptKeepsWhoSaidWhat() {
        AiLive.begin()
        AiLive.text("READY")
        AiLive.text("TRANSCRIPT caller This is Sam about the fence.")
        AiLive.text("TRANSCRIPT assistant Can I take a message?")
        val lines = AiLive.state.value.transcript
        assertEquals(2, lines.size)
        assertEquals("caller", lines[0].who)
        assertEquals("This is Sam about the fence.", lines[0].text)
        assertEquals("assistant", lines[1].who)
        assertEquals("Can I take a message?", lines[1].text)
        assertEquals("This is Sam about the fence.", AiLive.state.value.lastCallerLine)
        assertEquals("intro", AiLive.state.value.phase)
    }

    @Test fun aStateLineIsWhatTheAssistantIsDoing() {
        AiLive.begin()
        AiLive.text("STATE listening")
        assertEquals("Listening to the caller; your microphone is muted", AiLive.state.value.phaseWords)
        AiLive.text("STATE speaking")
        assertEquals("Speaking to the caller; your microphone is muted", AiLive.state.value.phaseWords)
    }

    @Test fun aHandoffIsKeptAsTheReason() {
        AiLive.begin()
        AiLive.text("EVENT handoff the caller asked for you")
        assertEquals("handoff", AiLive.state.value.phase)
        assertEquals("the caller asked for you", AiLive.state.value.endedReason)
    }

    @Test fun anEndKeepsTheLinesForTheReport() {
        AiLive.begin()
        AiLive.text("TRANSCRIPT caller Sam about the fence.")
        AiLive.text("END")
        assertFalse(AiLive.state.value.up)
        assertEquals("ended", AiLive.state.value.phase)
        assertEquals(1, AiLive.state.value.transcript.size)
    }

    @Test fun aReplayClearsTheLinesOnlyForACall() {
        AiLive.begin()
        AiLive.text("TRANSCRIPT caller one")
        AiLive.replay()
        assertTrue(AiLive.state.value.transcript.isEmpty())
        AiLive.end()
        AiLive.text("TRANSCRIPT caller two")
        AiLive.replay()
        // Not a call: a monitor that connected after the end must not wipe it.
        assertEquals(1, AiLive.state.value.transcript.size)
    }

    @Test fun aLineWithoutWordsIsDropped() {
        AiLive.begin()
        AiLive.text("TRANSCRIPT caller")
        AiLive.text("STATE ")
        assertTrue(AiLive.state.value.transcript.isEmpty())
        assertEquals("starting", AiLive.state.value.phase)
        assertNull(AiLive.state.value.endedReason)
    }
}
