package com.jemcik.jemrec.shell;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The frames of the AI link, the one loopback socket that carries a call's
 * audio to the on-phone assistant and its voice back.
 *
 * A frame is one type byte, four bytes of big-endian length, then the
 * payload. PCM is 16-bit little-endian: 48 kHz stereo towards the assistant
 * (the capture, unchanged), 48 kHz mono back (the injection track's format).
 * Text is UTF-8, one control line per frame, no terminator: HELLO, CALL,
 * READY, PLAYED, END from this side; END_TURN and HANGUP from the assistant.
 *
 * Plain Java, so daemon-tests holds the framing itself to its limits: a
 * length is never trusted, and a frame that cannot arrive whole is an error,
 * not a short read.
 */
final class AiFrames {

    private AiFrames() {
    }

    static final int TYPE_PCM = 1;
    static final int TYPE_TEXT = 2;

    /** A little over five seconds of the capture's stereo PCM; the capture
     *  sends a few dozen milliseconds at a time, and anything larger is a
     *  length that cannot be believed. */
    static final int MAX_PAYLOAD = 1 << 20;

    static final class Frame {
        final int type;
        final byte[] payload;

        Frame(int type, byte[] payload) {
            this.type = type;
            this.payload = payload;
        }

        String text() {
            return new String(payload, StandardCharsets.UTF_8);
        }
    }

    static Frame text(String line) {
        return new Frame(TYPE_TEXT, line.getBytes(StandardCharsets.UTF_8));
    }

    static Frame pcm(byte[] pcm) {
        return new Frame(TYPE_PCM, pcm);
    }

    static void write(OutputStream out, int type, byte[] payload) throws IOException {
        write(out, type, payload, 0, payload.length);
    }

    static void write(OutputStream out, int type, byte[] payload, int off, int length) throws IOException {
        if (length < 0 || length > MAX_PAYLOAD) {
            throw new IOException("frame of " + length + " bytes is not one this link sends");
        }
        byte[] header = new byte[5];
        header[0] = (byte) type;
        header[1] = (byte) (length >>> 24);
        header[2] = (byte) (length >>> 16);
        header[3] = (byte) (length >>> 8);
        header[4] = (byte) length;
        out.write(header);
        out.write(payload, off, length);
        out.flush();
    }

    static void write(OutputStream out, Frame frame) throws IOException {
        write(out, frame.type, frame.payload);
    }

    /** The next frame, or null at a clean end of the stream. */
    static @Nullable Frame read(DataInputStream in) throws IOException {
        int type;
        try {
            type = in.read();
        } catch (EOFException e) {
            return null;
        }
        if (type < 0) {
            return null;
        }
        if (type != TYPE_PCM && type != TYPE_TEXT) {
            throw new IOException("unknown frame type " + type);
        }
        int length;
        try {
            length = in.readInt();
        } catch (EOFException e) {
            throw new IOException("a frame header was cut short", e);
        }
        if (length < 0 || length > MAX_PAYLOAD) {
            throw new IOException("frame length " + length + " is not one this link sends");
        }
        byte[] payload = new byte[length];
        try {
            in.readFully(payload);
        } catch (EOFException e) {
            throw new IOException("a frame was cut short", e);
        }
        return new Frame(type, payload);
    }
}
