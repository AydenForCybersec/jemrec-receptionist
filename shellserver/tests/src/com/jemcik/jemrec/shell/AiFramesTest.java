package com.jemcik.jemrec.shell;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import org.junit.Test;

/** The AI link's frames on their own: both ways, the clean end, and the
 *  lengths a frame may not claim. Plus who the link lets in. */
public class AiFramesTest {

    private static DataInputStream read(byte[] bytes) {
        return new DataInputStream(new ByteArrayInputStream(bytes));
    }

    private static byte[] written(AiFrames.Frame frame, int type) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        AiFrames.write(bytes, type, frame.payload);
        return bytes.toByteArray();
    }

    @Test
    public void pcmGoesBothWaysUnchanged() throws IOException {
        byte[] pcm = {0, 1, 2, 3, (byte) 0xff, (byte) 0x80, 7, 8};
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AiFrames.write(out, AiFrames.TYPE_PCM, pcm);
        AiFrames.Frame frame = AiFrames.read(read(out.toByteArray()));
        assertEquals(AiFrames.TYPE_PCM, frame.type);
        assertArrayEquals(pcm, frame.payload);
    }

    @Test
    public void textIsUtf8AndWhole() throws IOException {
        AiFrames.Frame frame = AiFrames.text("PLAYED 1234");
        assertEquals(AiFrames.TYPE_TEXT, frame.type);
        assertEquals("PLAYED 1234", frame.text());
        AiFrames.Frame back = AiFrames.read(read(written(frame, AiFrames.TYPE_TEXT)));
        assertEquals("PLAYED 1234", back.text());
    }

    @Test
    public void aCleanEndIsNull() throws IOException {
        assertNull(AiFrames.read(read(new byte[0])));
    }

    @Test
    public void anUnknownTypeIsRefused() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(9);
        bytes.write(new byte[4]);
        try {
            AiFrames.read(read(bytes.toByteArray()));
            fail("a frame of an unknown type was read");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("unknown frame type"));
        }
    }

    @Test
    public void aLengthTooBigIsRefused() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(AiFrames.TYPE_PCM);
        out.writeInt(AiFrames.MAX_PAYLOAD + 1);
        try {
            AiFrames.read(read(bytes.toByteArray()));
            fail("a frame larger than the link allows was read");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("not one this link sends"));
        }
    }

    @Test
    public void aCutShortFrameIsAnErrorNotAnEnd() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(AiFrames.TYPE_PCM);
        out.writeInt(100);
        out.write(new byte[10]);
        try {
            AiFrames.read(read(bytes.toByteArray()));
            fail("a frame cut short was read");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("cut short"));
        }
    }

    @Test
    public void onlyTheShellAndTheOneAiAppAreAdmitted() {
        int ai = 10_321;
        assertTrue("shell, for probes", Checks.aiAllowed(Checks.SHELL_UID, ai));
        assertTrue("the AI app", Checks.aiAllowed(ai, ai));
        assertFalse("another app", Checks.aiAllowed(ai + 1, ai));
        assertFalse("no uid read", Checks.aiAllowed(-1, ai));
        assertFalse("Termux missing", Checks.aiAllowed(ai, -1));
        assertFalse("uid 0 is not a client", Checks.aiAllowed(0, ai));
    }
}
