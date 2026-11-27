package com.jemcik.jemrec.shell;

import com.genymobile.scrcpy.audio.AudioConfig;
import com.genymobile.scrcpy.util.Ln;

import android.media.AudioManager;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * THE LIVE LINK: one loopback socket that carries the call to the on-phone
 * assistant, and its voice back into the call.
 *
 * WHO MAY CONNECT. The AI link is the one command with no handshake: its
 * caller is the Termux app, which has no JemRec token. The check is the
 * kernel's - the uid owning the client's socket, from /proc/net/tcp, allowed
 * only when it is the shell's or the app named as the AI service (Main.
 * AI_PACKAGE) - the same table LoopbackServer already uses to refuse every
 * other caller. The app's own monitor uses the ordinary handshake and the
 * 'm' command, so it needs no second secret either.
 *
 * WHAT GOES OVER IT. AiFrames': the call's captured PCM out (48 kHz stereo,
 * 16-bit, as VOICE_CALL gives it) and the assistant's PCM in (48 kHz mono,
 * 16-bit, as the injection track takes it), with text control lines between:
 *
 *   daemon -> HELLO 1, CALL path=voice route=music, READY, PLAYED <ms>,
 *             END, ROUTE ...
 *   assistant -> HELLO, END_TURN, HANGUP, PING, STATUS
 *
 * READY is the 1.5 s lead-in's end: the assistant speaks only after it, as
 * the greeting only speaks after it, or the caller loses the first words
 * while the call's audio comes up. END_TURN is answered with PLAYED and the
 * milliseconds the track actually carried, so the service knows the caller
 * heard the line before it listens again.
 *
 * THE CAPTURE IS NEVER WAITED ON. Each session has its own sender thread and
 * a bounded queue: a slow or dead client loses its own audio, and the
 * capture's thread only ever offers. The injection side's queue blocks its
 * reader when the audio stack is behind, which is one socket's thread, never
 * the call.
 */
final class AiLink {

    private AiLink() {
    }

    static final int VERSION = 1;

    private static final CopyOnWriteArrayList<Session> SESSIONS = new CopyOnWriteArrayList<>();

    /** One call's audio, one at a time: started when the machine answers,
     *  ended with the call, reopened on the other route when a call moves. */
    private static final Object CALL_LOCK = new Object();
    private static volatile boolean callUp;
    private static volatile boolean ready;
    private static volatile @Nullable AssistantAudio audio;
    private static volatile long audioSeq;
    private static volatile String callLine = "";

    /** What the assistant said about this call, for a monitor that connects
     *  after it: TRANSCRIPT, STATE and EVENT lines, in order. Cleared when
     *  the next call starts; END tells the monitor the old one is over. */
    private static final Deque<String> HISTORY = new ArrayDeque<>();
    private static final int HISTORY_MAX = 60;

    /** The answering machine answered for the assistant: open its track on
     *  the route [path] takes, and tell every connected service. */
    static void callStarted(int path) {
        String route = CallDecisions.greetingRoute(Answering.answerSettings.route, path);
        AssistantAudio next;
        long seq;
        synchronized (CALL_LOCK) {
            stopAudioLocked();
            seq = ++audioSeq;
            ready = false;
            callUp = true;
            callLine = callLine(path, route);
            synchronized (HISTORY) {
                HISTORY.clear();
            }
            next = new AssistantAudio(route, listener(seq));
            audio = next;
        }
        next.start();
        broadcastControl(AiFrames.text(callLine));
        Ln.i("ai: call started, " + callLine);
    }

    /** The call is over, or the user took it: stop the track now, keep the
     *  clients connected for the next call. */
    static void callEnded() {
        boolean was;
        synchronized (CALL_LOCK) {
            was = callUp;
            callUp = false;
            ready = false;
            ++audioSeq;
            stopAudioLocked();
        }
        if (was) {
            broadcastControl(AiFrames.text("END"));
            Ln.i("ai: call ended");
        }
    }

    /** The call moved between its voice path and the mixer: the track is made
     *  again on the route the new path takes, the link stays up. */
    static void restart(int path) {
        String route = CallDecisions.greetingRoute(Answering.answerSettings.route, path);
        AssistantAudio next;
        long seq;
        synchronized (CALL_LOCK) {
            stopAudioLocked();
            seq = ++audioSeq;
            ready = false;
            callUp = true;
            callLine = callLine(path, route);
            next = new AssistantAudio(route, listener(seq));
            audio = next;
        }
        next.start();
        broadcastControl(AiFrames.text(callLine));
        Ln.i("ai: call moved, " + callLine);
    }

    private static String callLine(int path, String route) {
        return "CALL path=" + (path == Checks.PATH_MIXER ? "mixer" : "voice") + " route=" + route;
    }

    private static AssistantAudio.Listener listener(long seq) {
        return new AssistantAudio.Listener() {
            @Override
            public void ready() {
                if (seq == audioSeq && callUp) {
                    ready = true;
                    broadcastControl(AiFrames.text("READY"));
                    Ln.i("ai: the lead-in played; the assistant may speak");
                }
            }

            @Override
            public void failed(String why) {
                if (seq == audioSeq) {
                    Ln.e("ai: the assistant's track failed: " + why);
                    broadcastControl(AiFrames.text("ERROR audio " + why));
                }
            }
        };
    }

    private static void stopAudioLocked() {
        AssistantAudio a = audio;
        audio = null;
        if (a != null) {
            a.stop();
        }
    }

    /** A chunk of the call, as the recording read it: the caller's side, out
     *  to everyone listening. Called on the capture's thread. */
    static void onCallerPcm(short[] pcm, int frames, long atMs) {
        if (!callUp || SESSIONS.isEmpty() || frames <= 0) {
            return;
        }
        int samples = frames * AudioConfig.CHANNELS;
        byte[] bytes = new byte[samples * 2];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm, 0, samples);
        for (Session session : SESSIONS) {
            session.sendPcm(bytes);
        }
    }

    /** The Termux service's socket: it hears the call and speaks into it. */
    static void serveAi(Socket socket) {
        new Session(socket, true).serve();
    }

    /** The app's live monitor: it hears the call and can say nothing. */
    static void serveMonitor(Socket socket) {
        new Session(socket, false).serve();
    }

    /** The assistant asks for the call to end (its closing line has played):
     *  the machine hangs up from its own thread, as its limit does. */
    static void hangUp() {
        try {
            Answering.onMainEventually(() -> {
                AnswerMachine machine = Answering.answerMachine;
                if (machine != null) {
                    machine.hangUpNow();
                }
                return null;
            });
        } catch (Throwable t) {
            Ln.e("ai: could not ask the answering machine to hang up", t);
        }
    }

    private static void broadcastControl(AiFrames.Frame frame) {
        for (Session session : SESSIONS) {
            session.sendControl(frame);
        }
    }

    /** A transcript, state or event line from the assistant: kept for a late
     *  monitor, and sent to every other client. Never echoed to its sender. */
    private static void broadcastAssistantLine(String line, Session from) {
        if (isMonitorLine(line)) {
            synchronized (HISTORY) {
                if (HISTORY.size() >= HISTORY_MAX) {
                    HISTORY.removeFirst();
                }
                HISTORY.addLast(line);
            }
        }
        AiFrames.Frame frame = AiFrames.text(line);
        for (Session session : SESSIONS) {
            if (session != from) {
                session.sendControl(frame);
            }
        }
    }

    private static boolean isMonitorLine(String line) {
        return line.startsWith("TRANSCRIPT ") || line.startsWith("STATE ") || line.startsWith("EVENT ");
    }

    private static java.util.List<String> historyLines() {
        synchronized (HISTORY) {
            return new java.util.ArrayList<>(HISTORY);
        }
    }

    private static final class Session {

        private final Socket socket;
        private final boolean injector;
        private final BlockingQueue<AiFrames.Frame> outbox = new ArrayBlockingQueue<>(512);
        private volatile boolean closed;
        private long dropped;

        Session(Socket socket, boolean injector) {
            this.socket = socket;
            this.injector = injector;
        }

        void serve() {
            SESSIONS.add(this);
            try {
                socket.setTcpNoDelay(true);
                // No timeout: the socket is a call's lifetime, and the daemon's
                // command timeout (five seconds) is for headers, not for this.
                socket.setSoTimeout(0);
                Thread sender = new Thread(this::sendLoop, "jemrec-ai-send");
                sender.setDaemon(true);
                sender.start();
                sendControl(AiFrames.text("HELLO " + VERSION));
                if (callUp) {
                    sendControl(AiFrames.text(callLine));
                    if (ready) {
                        sendControl(AiFrames.text("READY"));
                    }
                }
                // A monitor that came late still sees what the assistant
                // said about this call: the recorder's replay of the same
                // lines the connected app already had.
                for (String line : historyLines()) {
                    sendControl(AiFrames.text(line));
                }
                if (injector) {
                    readLoop();
                } else {
                    drainLoop();
                }
            } catch (Throwable t) {
                if (!closed) {
                    Ln.w("ai: the link ended: " + t.getMessage());
                }
            } finally {
                closed = true;
                SESSIONS.remove(this);
                Shell.closeQuietly(socket);
                Ln.i("ai: a " + (injector ? "service" : "monitor") + " link closed"
                        + (dropped > 0 ? ", " + dropped + " frames dropped" : ""));
            }
        }

        /** The service's frames: speech up, control lines. */
        private void readLoop() throws IOException {
            DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            while (!closed) {
                AiFrames.Frame frame = AiFrames.read(in);
                if (frame == null) {
                    return;
                }
                if (frame.type == AiFrames.TYPE_PCM) {
                    AssistantAudio a = audio;
                    if (a != null) {
                        a.write(frame.payload);
                    }
                    continue;
                }
                String line = frame.text().trim();
                if (line.isEmpty()) {
                    continue;
                }
                switch (line) {
                    case "END_TURN": {
                        AssistantAudio a = audio;
                        long played = a == null ? 0 : a.endTurn();
                        sendControl(AiFrames.text("PLAYED " + played));
                        Ln.i("ai: line played, " + played + " ms");
                        break;
                    }
                    case "HANGUP":
                        sendControl(AiFrames.text("BYE"));
                        hangUp();
                        break;
                    case "PING":
                        sendControl(AiFrames.text("PONG"));
                        break;
                    case "STATUS":
                        sendControl(AiFrames.text(callUp ? callLine : "IDLE"));
                        break;
                    case "HELLO":
                        break;
                    default:
                        if (isMonitorLine(line)) {
                            broadcastAssistantLine(line, this);
                            Ln.i("ai: " + line);
                        } else {
                            Ln.w("ai: unknown control line '" + line + "'");
                        }
                        break;
                }
            }
        }

        /** The monitor's socket: its input is nothing, its close is the end. */
        private void drainLoop() throws IOException {
            InputStream in = socket.getInputStream();
            byte[] buf = new byte[1024];
            while (!closed && in.read(buf) >= 0) {
                // Nothing the monitor says is listened to.
            }
        }

        private void sendPcm(byte[] bytes) {
            if (closed || !outbox.offer(AiFrames.pcm(bytes))) {
                dropped++;
                if (dropped == 1 || dropped % 500 == 0) {
                    Ln.w("ai: " + dropped + " captured frames dropped for a slow client");
                }
            }
        }

        private void sendControl(AiFrames.Frame frame) {
            if (closed) {
                return;
            }
            try {
                if (!outbox.offer(frame, 500, TimeUnit.MILLISECONDS)) {
                    Ln.w("ai: a control line could not be sent: " + frame.text());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void sendLoop() {
            try {
                while (!closed) {
                    AiFrames.Frame frame = outbox.take();
                    AiFrames.write(socket.getOutputStream(), frame);
                }
            } catch (InterruptedException ignored) {
                // Closing.
            } catch (IOException e) {
                if (!closed) {
                    Ln.w("ai: send failed: " + e.getMessage());
                }
            } finally {
                closed = true;
                Shell.closeQuietly(socket);
            }
        }
    }

}
