package com.jemcik.jemrec.shell;

import com.genymobile.scrcpy.util.Ln;

import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.SystemClock;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The assistant's voice on the wire the caller hears: one track per answered
 * call, kept open from the answer to its end, with the call's uplink as its
 * preferred device (in-call music) or Android's call-screening track for a
 * call through the mixer. The routes and their measurements are
 * GreetingPlayer's, and the tracks are the same two.
 *
 * WHY ONE TRACK, NOT ONE PER LINE. The greeting player's track is made for
 * its greeting and released after the beep. An assistant speaks several
 * times in one call, and a track made for each line would be made before the
 * call's audio is up each time, lose its first words to the 1.5 s the call
 * takes to come up (measured on an Honor, 2026-10-22), and, on the
 * call-screening route, be released by a mode the call moves through. So one
 * track is made when the call is answered, 1.5 s of silence goes first and is
 * reported as READY, and between lines it is fed silence: the route stays up
 * and every line is written into an output that is already playing.
 *
 * THE WRITER THREAD OWNS THE TRACK. Every write to an AudioTrack comes from
 * it, in order; callers hand it PCM through a bounded queue and wait for the
 * end of a line (endTurn) by the track's own playback head, which is the only
 * clock that says the caller actually heard it. The queue blocks the AI
 * link's reader when the phone is behind, never the call's capture.
 */
final class AssistantAudio {

    static final int RATE = GreetingPlayer.RATE;

    interface Listener {
        /** The lead-in has played: the first line may go out. */
        void ready();

        void failed(String why);
    }

    /** 100 blocks of 20 ms: two seconds of queued speech before the AI link's
     *  reader waits, far more than a spoken line. */
    private static final int QUEUE_BLOCKS = 100;
    private static final int BLOCK_BYTES = RATE / 50 * 2;
    private static final int SILENCE_MS = 100;
    private static final long END_TURN_WAIT_MS = 30_000L;
    private static final long HEAD_POLL_MS = 20L;

    private final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(QUEUE_BLOCKS);
    private final Listener listener;
    private final String route;

    private volatile @Nullable AudioTrack track;
    private volatile boolean stopping;
    private volatile boolean failed;
    /** Frames at or after which everything asked for has played: the lead-in,
     *  then each block of speech as it is queued. Counted from two threads -
     *  the link's reader queues, the writer writes the lead-in. */
    private final AtomicLong framesQueued = new AtomicLong();
    private volatile @Nullable Thread writer;

    AssistantAudio(String route, Listener listener) {
        this.route = route;
        this.listener = listener;
    }

    /** Starts the writer; the track itself is made on that thread, so a call's
     *  answer never waits on the audio stack. */
    void start() {
        Thread t = new Thread(this::run, "jemrec-assistant-audio");
        t.setDaemon(true);
        writer = t;
        t.start();
    }

    /** Hands the line to the writer, in 20 ms blocks. Waits when two seconds
     *  are already queued. */
    void write(byte[] pcm) {
        if (stopping || failed || pcm.length == 0) {
            return;
        }
        int off = 0;
        while (off < pcm.length && !stopping && !failed) {
            int n = Math.min(BLOCK_BYTES, pcm.length - off);
            if ((n & 1) != 0) {
                n--;
            }
            if (n <= 0) {
                return;
            }
            byte[] block = new byte[n];
            System.arraycopy(pcm, off, block, 0, n);
            framesQueued.addAndGet(n / 2);
            try {
                if (!queue.offer(block, 500, TimeUnit.MILLISECONDS)) {
                    // Counted, then not queued: never waited for as played.
                    framesQueued.addAndGet(-n / 2);
                    Ln.w("assistant: two seconds of speech queued and the track is not taking more; dropping the rest of the line");
                    return;
                }
            } catch (InterruptedException e) {
                framesQueued.addAndGet(-n / 2);
                Thread.currentThread().interrupt();
                return;
            }
            off += n;
        }
    }

    /** Waits until every speech frame queued so far has played, and returns
     *  how much audio that was in ms (0 if the track is gone). */
    long endTurn() {
        AudioTrack tr = track;
        if (tr == null) {
            return 0;
        }
        long untilFrames = framesQueued.get();
        try {
            long deadline = SystemClock.elapsedRealtime() + END_TURN_WAIT_MS;
            while (!stopping && SystemClock.elapsedRealtime() < deadline) {
                if ((tr.getPlaybackHeadPosition() & 0xffffffffL) >= untilFrames) {
                    return untilFrames * 1000L / RATE;
                }
                SystemClock.sleep(HEAD_POLL_MS);
            }
        } catch (Throwable t) {
            Ln.w("assistant: could not wait for the playback head: " + t.getMessage());
        }
        return 0;
    }

    void stop() {
        stopping = true;
        AudioTrack tr = track;
        if (tr != null) {
            try {
                tr.pause();
                tr.flush();
            } catch (Throwable ignored) {
                // Released under it: nothing to stop.
            }
        }
        Thread t = writer;
        if (t != null) {
            t.interrupt();
        }
        queue.clear();
    }

    private void run() {
        AudioTrack tr = null;
        try {
            AudioManager audio = Platform.callAudio;
            if (audio == null) {
                throw new IllegalStateException("no audio manager");
            }
            tr = AnswerSettings.ROUTE_SCREENING.equals(route)
                    ? GreetingPlayer.screeningTrack(audio)
                    : GreetingPlayer.musicTrack(audio);
            track = tr;
            if (stopping) {
                return;
            }
            tr.play();
            long leadFrames = GreetingPlayer.framesOf(GreetingPlayer.LEAD_IN_MS);
            framesQueued.addAndGet(leadFrames);
            writeFully(tr, new byte[(int) leadFrames * 2]);
            // The first words of a call are lost until its audio is up: the
            // lead-in is 1.5 s for that, and READY says it is behind us.
            if (waitPlayed(tr, leadFrames)) {
                listener.ready();
            }
            while (!stopping) {
                byte[] block = queue.poll(50, TimeUnit.MILLISECONDS);
                if (block == null) {
                    if (stopping) {
                        break;
                    }
                    writeFully(tr, new byte[GreetingPlayer.framesOf(SILENCE_MS) * 2]);
                } else {
                    writeFully(tr, block);
                }
            }
        } catch (Throwable t) {
            if (!stopping) {
                failed = true;
                Ln.e("assistant: the track failed on route " + route, t);
                listener.failed(t.getClass().getSimpleName());
            }
        } finally {
            track = null;
            if (tr != null) {
                try {
                    tr.stop();
                } catch (Throwable ignored) {
                    // Not playing.
                }
                try {
                    tr.release();
                } catch (Throwable ignored) {
                    // Released already.
                }
            }
        }
    }

    private static void writeFully(AudioTrack tr, byte[] data) {
        int off = 0;
        while (off < data.length) {
            int n = tr.write(data, off, data.length - off);
            if (n < 0) {
                throw new IllegalStateException("AudioTrack.write returned " + n);
            }
            off += n;
        }
    }

    private boolean waitPlayed(AudioTrack tr, long frames) {
        long deadline = SystemClock.elapsedRealtime() + GreetingPlayer.LEAD_IN_MS + 5_000L;
        while (!stopping && (tr.getPlaybackHeadPosition() & 0xffffffffL) < frames) {
            if (SystemClock.elapsedRealtime() > deadline) {
                Ln.w("assistant: the lead-in did not play in time");
                return false;
            }
            SystemClock.sleep(HEAD_POLL_MS);
        }
        return !stopping;
    }
}
