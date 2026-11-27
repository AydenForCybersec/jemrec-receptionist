package com.jemcik.jemrec.shell;

import com.genymobile.scrcpy.audio.AudioCapture;
import com.genymobile.scrcpy.audio.AudioConfig;
import com.genymobile.scrcpy.util.Ln;

import android.media.MediaCodec;
import android.os.SystemClock;

import java.nio.ByteBuffer;

/**
 * The assistant's call, captured with nothing written: the same phone-call
 * capture the recording session makes, switching to the mixer where the call
 * plays through it, read on a thread of its own and handed to the answering
 * machine and the AI link. No file and no encoder, because this fork keeps no
 * recording of a call the assistant takes.
 *
 * One per assistant call, started when the call is the assistant's and
 * stopped when it is over or the user takes it. A capture that fails is made
 * anew by Restarting, as a recording's would be; a capture that never comes
 * back ends the pump, and the machine's limit ends the call.
 */
final class CallAudioPump {

    private static volatile @Nullable CallAudioPump current;

    /** Capture the call for the assistant, replacing any pump left over.
     *  Failures are logged: the call goes on, silent to the service. */
    static void start(boolean incoming) {
        stop();
        try {
            current = new CallAudioPump(incoming);
        } catch (Throwable t) {
            Ln.e("call audio: could not capture the call for the assistant", t);
        }
    }

    /** The assistant's call is over, or the user took it. */
    static void stop() {
        CallAudioPump pump = current;
        current = null;
        if (pump != null) {
            pump.close();
        }
    }

    private final AudioCapture tapped;
    private final Thread reader;
    private volatile boolean stopped;

    private CallAudioPump(boolean incoming) throws Exception {
        // The report is the recording's Copy a report; kept for the log's
        // sake, never written. The call is one this process answered.
        CallReport report = new CallReport(Checks.callInWords(incoming, null, null, true),
                Quality.words(Quality.bitRate), SystemClock::elapsedRealtime);
        AudioCapture first = RecordingSession.phoneCapture(report, null);
        Restarting restarted = new Restarting(first,
                failed -> RecordingSession.phoneCapture(report,
                        failed instanceof PhoneCapture ? ((PhoneCapture) failed).path() : null),
                report, new Restarting.Told() {
                    @Override
                    public void stopped(boolean forGood, long sinceMs) {
                        Ln.w("call audio: the capture stopped"
                                + (forGood ? "; it is not tried again" : "; it is made anew"));
                    }

                    @Override
                    public void back() {
                        Ln.i("call audio: the capture is back");
                    }
                });
        // The level tap is the recording's, unchanged: the machine's levels
        // (for a limit and a takeover), the AI link's caller side, and where
        // the call's first sample was.
        tapped = new LevelTap(restarted, (pcm, frames, atMs) -> {
            report.chunk(pcm, frames, AudioConfig.CHANNELS);
            AiLink.onCallerPcm(pcm, frames, atMs);
            Answering.onChunk(pcm, frames, atMs);
        });
        tapped.checkCompatibility();
        tapped.start();
        reader = new Thread(this::read, "jemrec-call-audio");
        reader.setDaemon(true);
        reader.start();
        Ln.i("call audio: the assistant's call is being captured; no recording is kept");
    }

    private void read() {
        ByteBuffer buffer = ByteBuffer.allocateDirect(AudioConfig.MAX_READ_SIZE);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (!stopped) {
                int r = tapped.read(buffer, info);
                if (r <= 0) {
                    Ln.w("call audio: the capture ended (" + r + "); the assistant hears no more");
                    return;
                }
            }
        } catch (Throwable t) {
            if (!stopped) {
                Ln.e("call audio: the capture failed", t);
            }
        }
    }

    private void close() {
        stopped = true;
        reader.interrupt();
        try {
            tapped.stop();
        } catch (Throwable t) {
            Ln.w("call audio: error while stopping the capture: " + t.getMessage());
        }
        // Not joined: this runs on the call watch's looper. Stopping the
        // capture makes the blocked read return, and the reader is a daemon
        // that then ends; anything it still taps goes to a link already told
        // the call is over.
        Ln.i("call audio: stopped");
    }
}
