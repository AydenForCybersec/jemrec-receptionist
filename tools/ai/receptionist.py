#!/usr/bin/env python3
"""M3: the on-phone receptionist service, run in Termux.

It connects to JemRec's AI link on loopback, hears the call, detects the
caller's turns, transcribes them with whisper.cpp, answers them with a local
llama.cpp model (or a canned line when none is configured), speaks the reply
with Piper, and asks the recorder to hang up after the last turn. No cloud,
no accounts, no third-party Python packages.

    python receptionist.py

Configuration is by flags or environment; the defaults expect:

    ~/whisper.cpp/build/bin/whisper-cli
    ~/whisper.cpp/models/ggml-base.en.bin
    ~/piper/piper
    ~/piper/en_US-lessac-medium.onnx
    ~/llama.cpp/build/bin/llama-server
    ~/models/Llama-3.2-3B-Instruct-Q4_K_M.gguf

This is M3: a local LLM, the safety rails, assistant mode and the live
transcript. The first line of every call is still a fixed automated line,
spoken before the model is asked anything.

The service is deliberately a foreground script: Termux keeps it alive while
the screen is off with `termux-wake-lock`, which it asks for itself when that
command is present.
"""

import argparse
import array
import atexit
import json
import logging
import math
import os
import queue
import re
import shlex
import shutil
import signal
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
import wave

HOST = "127.0.0.1"
PORT = 28472
TYPE_PCM = 1
TYPE_TEXT = 2
MAX_PAYLOAD = 1 << 20
RATE = 48000
CHANNELS = 2
SAMPLE_BYTES = 2

DEFAULT_OWNER = "the owner"
OWNER = " ".join((os.environ.get("JEMREC_OWNER") or DEFAULT_OWNER).split())[:40] or DEFAULT_OWNER


def set_owner(name):
    """Set the name the assistant answers for; the default is neutral."""
    global OWNER
    cleaned = " ".join((name or "").split()).strip()[:40]
    OWNER = cleaned or DEFAULT_OWNER


def owner_sentence():
    return OWNER[:1].upper() + OWNER[1:]


def intro():
    return (f"Hi, this is an automated assistant answering for {OWNER}. "
            "Who is calling, and what is this about?")


def closing():
    return f"Thanks. I'll pass that on to {OWNER}. Goodbye."


def missed():
    return "Sorry, I didn't catch that. Who is calling, and what is this about?"


def identity():
    return f"I'm an automated assistant. I can take a message for {OWNER}."


def unavailable():
    return (f"{owner_sentence()} can't come to the phone right now. "
            "I can take a message.")


def handoff():
    return (f"{owner_sentence()} can't come to the phone right now. "
            "I'll let them know you called. Can I take a message?")


def take_message():
    return f"Go ahead. I'll pass it on to {OWNER}."


def ack():
    return f"Thanks. I'll let {OWNER} know. Can I take a message?"


EMERGENCY = "If this is an emergency, please hang up and call emergency services."
BYE = "Thanks for calling. Goodbye."


def safe_fallback():
    return ("I can't share that. I'm an automated assistant, "
            f"and I can take a message for {OWNER}.")

# The safety rail after the model speaks. A reply with any of these is not
# spoken; the fixed fallback line is, instead. The first line of the call is
# ours, not the model's, so it always says the assistant is automated.
BANNED_PHRASES = (
    "i promise", "i'll promise", "i will promise", "i guarantee",
    "i'll pay", "i will pay", "i'll book", "i will book",
    "i'll sign", "i will sign", "i'll send money", "i will send money",
    "i'll transfer", "i will transfer", "i commit", "you have my word",
    "i'll be there", "i will be there", "i agree to", "consider it done",
    "i'll take care of it", "social security", "bank account",
    "card number", "credit card", "password", "passcode",
    "my address is", "he lives at", "she lives at", "his address",
    "his phone", "his number", "his email", "her phone", "her number",
)


def banned_phrases():
    name = OWNER.lower()
    return BANNED_PHRASES + (
        f"i am {name}", f"this is {name}", f"{name} speaking", f"i'm {name}",
    )


BANNED_PATTERNS = (
    re.compile(r"@[\w.-]+\.[a-z]{2,}", re.IGNORECASE),
    re.compile(r"(?:https?://|www\.)", re.IGNORECASE),
    re.compile(r"\+?\d[\d\s().-]{6,}\d"),
)
HANDOFF_WORDS = (
    "real person", "speak to a human", "talk to a human", "have him call",
    "when will he", "when is he back", "call me back",
)


def handoff_words():
    name = OWNER.lower()
    return HANDOFF_WORDS + (
        f"speak to {name}", f"talk to {name}", f"is {name} there",
        f"have {name} call", f"when will {name}",
    )

log = logging.getLogger("receptionist")


# ---------------------------------------------------------------- the link

def frame(kind, payload):
    return struct.pack(">BI", kind, len(payload)) + payload


class Link:
    """The daemon's AI socket: caller PCM and control lines in, speech out.

    One reader thread reconnects for as long as the service runs. The main
    thread only sends, under a lock; a call's audio is never waited on by
    the capture.
    """

    def __init__(self, on_line, on_pcm):
        self.on_line = on_line
        self.on_pcm = on_pcm
        self.sock = None
        self.send_lock = threading.Lock()
        self.connected = threading.Event()
        self.call_up = False
        self.ready = False
        self.ready_event = threading.Event()
        self.played_event = threading.Event()
        self.played_ms = 0
        self.call_started_at = 0.0
        self.call_line = ""
        self._stop = False
        self._thread = None

    def start(self):
        self._thread = threading.Thread(target=self._run, name="ai-link", daemon=True)
        self._thread.start()

    def close(self):
        self._stop = True
        self._shut()
        if self._thread is not None:
            self._thread.join(2.0)

    def _shut(self):
        s = self.sock
        self.sock = None
        self.connected.clear()
        self.call_up = False
        self.ready = False
        self.ready_event.clear()
        self.played_event.set()
        if s is not None:
            try:
                s.close()
            except OSError:
                pass

    def _run(self):
        while not self._stop:
            try:
                s = socket.create_connection((HOST, PORT), timeout=5.0)
                s.settimeout(1.0)
                s.sendall(b"a")
                self.sock = s
                self.connected.set()
                log.info("connected to the recorder")
                self._read_loop(s)
            except OSError as e:
                if not self._stop:
                    log.warning("link: %s", e)
            finally:
                self._shut()
            if not self._stop:
                time.sleep(2.0)
        log.info("link closed")

    def _read_loop(self, s):
        rx = bytearray()
        while not self._stop:
            try:
                chunk = s.recv(65536)
            except socket.timeout:
                continue
            except OSError:
                return
            if not chunk:
                return
            rx.extend(chunk)
            while len(rx) >= 5:
                kind = rx[0]
                (length,) = struct.unpack(">I", rx[1:5])
                if length > MAX_PAYLOAD:
                    log.error("link: frame of %d bytes is too large", length)
                    return
                if len(rx) < 5 + length:
                    break
                payload = bytes(rx[5:5 + length])
                del rx[:5 + length]
                if kind == TYPE_PCM:
                    self.on_pcm(payload, time.monotonic())
                elif kind == TYPE_TEXT:
                    self._line(payload.decode("utf-8", "replace").strip())

    def _line(self, line):
        if not line:
            return
        if line.startswith("CALL"):
            was_up = self.call_up
            self.call_up = True
            # A CALL is also sent when the call moves to the other route: its
            # track is new, and READY has to come again before a line.
            self.ready = False
            self.ready_event.clear()
            self.call_line = line
            if not was_up:
                self.call_started_at = time.monotonic()
            log.info("%s", line)
        elif line == "READY":
            self.ready = True
            self.ready_event.set()
            log.info("the call is ready; the assistant may speak")
        elif line.startswith("PLAYED"):
            try:
                self.played_ms = int(line.split()[1])
            except (IndexError, ValueError):
                self.played_ms = 0
            self.played_event.set()
        elif line == "END":
            self.call_up = False
            self.ready = False
            self.ready_event.clear()
            self.played_event.set()
            log.info("the call ended")
        elif line.startswith("ERROR"):
            log.error("%s", line)
        elif line in ("BYE", "PONG", "IDLE") or line.startswith("HELLO"):
            log.info("%s", line)
        else:
            log.info("%s", line)
        self.on_line(line)

    def send_pcm(self, pcm):
        self._send(TYPE_PCM, pcm)

    def send_text(self, line):
        self._send(TYPE_TEXT, line.encode("utf-8"))

    def _send(self, kind, payload):
        with self.send_lock:
            s = self.sock
            if s is None:
                raise ConnectionError("the link is not connected")
            s.sendall(frame(kind, payload))


# ---------------------------------------------------------------- audio

def stereo_to_mono(pcm):
    samples = array.array("h")
    samples.frombytes(pcm)
    if sys.byteorder != "little":
        samples.byteswap()
    if len(samples) % 2:
        samples = samples[:-1]
    mono = array.array("h", [0]) * (len(samples) // 2)
    for i in range(0, len(samples), 2):
        mono[i // 2] = (samples[i] + samples[i + 1]) // 2
    return mono


def to_16k(samples):
    # Box average over three 48 kHz samples: 16 kHz, and a little anti-alias.
    n = len(samples) // 3
    out = array.array("h", [0]) * n
    for i in range(n):
        j = i * 3
        out[i] = (samples[j] + samples[j + 1] + samples[j + 2]) // 3
    return out


def resample(samples, src_rate, dst_rate):
    if src_rate == dst_rate:
        return samples
    if len(samples) == 0:
        return samples
    n = int(len(samples) * dst_rate / src_rate)
    out = array.array("h", [0]) * n
    step = src_rate / dst_rate
    for i in range(n):
        pos = i * step
        j = int(pos)
        frac = pos - j
        a = samples[j]
        b = samples[j + 1] if j + 1 < len(samples) else a
        out[i] = int(a + (b - a) * frac)
    return out


def write_wav(path, samples, rate, channels=1):
    with wave.open(path, "wb") as w:
        w.setnchannels(channels)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(samples.tobytes())


def read_wav(path):
    with wave.open(path, "rb") as w:
        if w.getsampwidth() != 2:
            raise ValueError("audio is not 16-bit PCM")
        rate = w.getframerate()
        channels = w.getnchannels()
        raw = w.readframes(w.getnframes())
    samples = array.array("h")
    samples.frombytes(raw)
    if sys.byteorder != "little":
        samples.byteswap()
    if channels == 2:
        samples = stereo_to_mono(samples)
    elif channels != 1:
        raise ValueError("audio has %d channels" % channels)
    return samples, rate


class Vad:
    """Energy end-of-turn detection on the caller's side.

    Frames are 30 ms of 48 kHz mono. A turn starts after three voiced frames,
    keeps a short pre-roll, and ends after `silence_ms` of quiet or at the
    maximum length. Speech shorter than `min_speech_ms` is a click, not a
    turn. The assistant's own voice is kept out by the service, not here.
    """

    def __init__(self, rate=RATE, threshold=250, silence_ms=900,
                 min_speech_ms=250, max_utterance_ms=12000, pre_roll_ms=180):
        self.rate = rate
        self.frame_ms = 30
        self.frame_samples = rate * self.frame_ms // 1000
        self.threshold = threshold
        self.silence_frames = max(1, silence_ms // self.frame_ms)
        self.min_voiced_frames = max(1, min_speech_ms // self.frame_ms)
        self.max_frames = max(1, max_utterance_ms // self.frame_ms)
        self.pre_roll_frames = max(1, pre_roll_ms // self.frame_ms)
        self.buf = array.array("h")
        self.pre = []
        self.cur = []
        self.in_speech = False
        self.voiced_run = 0
        self.silence_run = 0
        self.voiced_frames = 0

    def reset(self):
        self.buf = array.array("h")
        self._reset_turn()

    def _reset_turn(self):
        self.pre = []
        self.cur = []
        self.in_speech = False
        self.voiced_run = 0
        self.silence_run = 0
        self.voiced_frames = 0

    def feed_stereo(self, pcm):
        return self.feed(stereo_to_mono(pcm))

    def feed(self, mono):
        self.buf.extend(mono)
        turns = []
        while len(self.buf) >= self.frame_samples:
            frame = self.buf[:self.frame_samples]
            del self.buf[:self.frame_samples]
            turn = self._frame(frame)
            if turn is not None:
                turns.append(turn)
        return turns

    def _frame(self, frame):
        rms = math.sqrt(sum(x * x for x in frame) / len(frame))
        voiced = rms >= self.threshold
        if not self.in_speech:
            self.pre.append(frame)
            if len(self.pre) > self.pre_roll_frames:
                self.pre.pop(0)
            if voiced:
                self.voiced_run += 1
                if self.voiced_run >= 3:
                    self.in_speech = True
                    self.cur = list(self.pre)
                    self.voiced_frames = sum(1 for f in self.cur if rms_of(f) >= self.threshold)
                    self.silence_run = 0
                    self.pre = []
            else:
                self.voiced_run = 0
            return None

        self.cur.append(frame)
        if voiced:
            self.voiced_frames += 1
            self.silence_run = 0
        else:
            self.silence_run += 1
        enough_silence = self.silence_run >= self.silence_frames
        too_long = len(self.cur) >= self.max_frames
        if not enough_silence and not too_long:
            return None

        # Keep a little of the tail, drop the long quiet that ended the turn.
        keep = len(self.cur) - max(0, self.silence_run - 6)
        kept = self.cur[:max(1, keep)]
        turn = array.array("h")
        for f in kept:
            turn.extend(f)
        voiced = self.voiced_frames >= self.min_voiced_frames
        # Only the turn: the samples after this frame in buf are still to be
        # read, and reset() would drop them.
        self._reset_turn()
        return bytes(turn) if voiced else None


def rms_of(samples):
    return math.sqrt(sum(x * x for x in samples) / max(1, len(samples)))


# ---------------------------------------------------------------- speech

class Whisper:
    def __init__(self, binary, model, threads=4, timeout=45):
        self.command = shlex.split(binary or "")
        self.model = model
        self.threads = threads
        self.timeout = timeout

    def available(self):
        return (bool(self.command) and os.path.exists(self.model)
                and (os.path.isabs(self.command[0]) or shutil.which(self.command[0])))

    def transcribe(self, pcm48):
        samples = array.array("h")
        samples.frombytes(pcm48)
        if sys.byteorder != "little":
            samples.byteswap()
        work = tempfile.mkdtemp(prefix="jemrec-stt-")
        wav = os.path.join(work, "turn.wav")
        prefix = os.path.join(work, "turn")
        try:
            write_wav(wav, to_16k(samples), 16000)
            cmd = (self.command + ["-m", self.model, "-f", wav, "-l", "en",
                                   "-nt", "-otxt", "-of", prefix,
                                   "-t", str(self.threads)])
            result = subprocess.run(cmd, capture_output=True, text=True,
                                    timeout=self.timeout)
            if result.returncode != 0:
                log.error("whisper: exit %d: %s", result.returncode,
                          (result.stderr or "").strip()[-300:])
                return ""
            text = ""
            out = prefix + ".txt"
            if os.path.exists(out):
                with open(out, "r", encoding="utf-8", errors="replace") as f:
                    text = f.read()
            if not text.strip():
                text = result.stdout
            return clean_transcript(text)
        except subprocess.TimeoutExpired:
            log.error("whisper: no answer in %d s", self.timeout)
            return ""
        except (OSError, ValueError) as e:
            log.error("whisper: %s", e)
            return ""
        finally:
            shutil.rmtree(work, ignore_errors=True)


def clean_transcript(text):
    lines = []
    for line in text.splitlines():
        line = line.strip()
        if not line or "-->" in line:
            continue
        lines.append(line)
    text = " ".join(lines)
    for tag in ("[BLANK_AUDIO]", "[ Silence ]", "[silence]", "(silence)",
                "[MUSIC]", "[music]", "[NOISE]", "[noise]"):
        text = text.replace(tag, " ")
    return " ".join(text.split()).strip()


class Piper:
    def __init__(self, binary, model, timeout=30, raw_rate=22050, raw_format="s16"):
        self.command = shlex.split(binary or "")
        self.model = model
        self.timeout = timeout
        self.raw_rate = raw_rate
        self.raw_format = raw_format
        self.cache = {}

    def available(self):
        # A Termux piper package may take the voice's name and find the file
        # through PIPER_VOICE_PATH; the official CLI takes the path.
        model_here = os.path.exists(self.model) or bool(os.environ.get("PIPER_VOICE_PATH"))
        return (bool(self.command) and model_here
                and (os.path.isabs(self.command[0]) or shutil.which(self.command[0])))

    def synth(self, text):
        if text in self.cache:
            return self.cache[text]
        work = tempfile.mkdtemp(prefix="jemrec-tts-")
        out = os.path.join(work, "line.wav")
        try:
            cmd = self.command + ["-m", self.model, "-f", out]
            result = subprocess.run(cmd, input=text + "\n", capture_output=True,
                                    text=True, timeout=self.timeout)
            if result.returncode != 0:
                raise RuntimeError("piper exit %d: %s" % (
                    result.returncode, (result.stderr or "").strip()[-300:]))
            if not os.path.exists(out) or os.path.getsize(out) < 44:
                raise RuntimeError("piper wrote no audio")
            with open(out, "rb") as f:
                head = f.read(4)
            if head == b"RIFF":
                samples, rate = read_wav(out)
            else:
                # The Termux piper package writes headerless 16-bit mono.
                with open(out, "rb") as f:
                    raw = f.read()
                if self.raw_format == "f32":
                    floats = array.array("f")
                    floats.frombytes(raw)
                    if sys.byteorder != "little":
                        floats.byteswap()
                    samples = array.array("h", [0]) * len(floats)
                    for i, v in enumerate(floats):
                        samples[i] = max(-32768, min(32767, int(v * 32767)))
                else:
                    samples = array.array("h")
                    samples.frombytes(raw)
                    if sys.byteorder != "little":
                        samples.byteswap()
                rate = self.raw_rate
            samples = resample(samples, rate, RATE)
            pcm = samples.tobytes()
            self.cache[text] = pcm
            log.info("piper: %d ms of speech", len(samples) * 1000 // RATE)
            return pcm
        except subprocess.TimeoutExpired:
            raise RuntimeError("piper: no answer in %d s" % self.timeout)
        finally:
            shutil.rmtree(work, ignore_errors=True)


# ---------------------------------------------------------------- language

class Llm:
    """A local llama.cpp server, spoken to over its OpenAI-compatible API.

    The model is started once, when the service starts, not per turn: a 3B
    model takes many seconds to load, and a caller will not wait. When a
    server is already running, pass its URL and nothing is started here.
    """

    def __init__(self, url=None, server=None, model=None, port=8081,
                 threads=4, ctx=2048, timeout=25):
        self.url = url or ("http://127.0.0.1:%d" % port if server else None)
        self.server = server
        self.model = model
        self.port = port
        self.threads = threads
        self.ctx = ctx
        self.timeout = timeout
        self.process = None
        self.log = None
        self.ready = False

    def available(self):
        if self.url:
            return True
        return bool(self.server and self.model and os.path.exists(self.model))

    def start(self):
        """Start the server if this service owns it, and wait for it."""
        if not self.available():
            return False
        if self.server:
            self.log = open(os.path.join(tempfile.gettempdir(), "jemrec-llama.log"), "ab")
            cmd = [self.server, "-m", self.model, "-c", str(self.ctx),
                   "--host", "127.0.0.1", "--port", str(self.port),
                   "-t", str(self.threads)]
            log.info("llm: starting %s", " ".join(shlex.quote(c) for c in cmd))
            try:
                self.process = subprocess.Popen(cmd, stdout=self.log, stderr=self.log)
            except OSError as e:
                log.error("llm: could not start llama-server: %s", e)
                return False
        # A model this service started takes a while to load; a server
        # someone else started should already be up.
        deadline = time.monotonic() + (180 if self.server else 20)
        while time.monotonic() < deadline:
            if self.process is not None and self.process.poll() is not None:
                log.error("llm: llama-server exited with %s; see %s",
                          self.process.returncode,
                          os.path.join(tempfile.gettempdir(), "jemrec-llama.log"))
                return False
            if self._healthy():
                self.ready = True
                log.info("llm: ready at %s", self.url)
                return True
            time.sleep(1.0)
        log.error("llm: no answer from %s in 180 s", self.url)
        return False

    def _healthy(self):
        try:
            with urllib.request.urlopen(self.url.rstrip("/") + "/health", timeout=2) as r:
                return r.status == 200
        except (urllib.error.URLError, OSError, ValueError):
            return False

    def reply(self, system, messages):
        body = {
            "messages": [{"role": "system", "content": system}] + messages,
            "max_tokens": 90,
            "temperature": 0.45,
            "top_p": 0.9,
            "stop": ["\n\n", "\nCaller:", "\nUser:", f"\n{owner_sentence()}:"],
        }
        request = urllib.request.Request(
            self.url.rstrip("/") + "/v1/chat/completions",
            data=json.dumps(body).encode("utf-8"),
            headers={"Content-Type": "application/json"},
        )
        with urllib.request.urlopen(request, timeout=self.timeout) as r:
            answer = json.load(r)
        return answer["choices"][0]["message"]["content"]

    def stop(self):
        if self.process is not None:
            try:
                self.process.terminate()
                self.process.wait(timeout=5)
            except (OSError, subprocess.TimeoutExpired):
                try:
                    self.process.kill()
                except OSError:
                    pass
            self.process = None
        if self.log is not None:
            try:
                self.log.close()
            except OSError:
                pass
            self.log = None


def sanitize_reply(text):
    """The model's line, or None when it must not be spoken.

    Short, plain speech only. No numbers long enough to be a phone number, no
    email or URL, no personal detail, and no promise, booking, payment or
    agreement. The caller hears safe_fallback() instead when this says no.
    """
    if not text:
        return None
    text = text.replace("*", "").replace("#", "").replace("`", "")
    text = " ".join(text.split()).strip().strip('"\'')
    if not text:
        return None
    lower = " " + text.lower() + " "
    if any(p in lower for p in banned_phrases()):
        return None
    if any(p.search(text) for p in BANNED_PATTERNS):
        return None
    if len(text) > 260 or len(text.split()) > 45:
        cut = max(text.rfind(". "), text.rfind("? "), text.rfind("! "))
        if cut < 40:
            return None
        text = text[:cut + 1]
    # Two sentences at most: a phone line is not an essay.
    parts = re.split(r"(?<=[.!?])\s+", text)
    if len(parts) > 2:
        text = " ".join(parts[:2])
    return text.strip() or None


def wants_handoff(transcript):
    t = " " + transcript.lower() + " "
    return any(w in t for w in handoff_words())


def facts_for_prompt(path):
    """The owner's own notes, capped small: the model may use only these."""
    try:
        with open(path, "r", encoding="utf-8", errors="replace") as f:
            text = f.read(1600)
    except OSError:
        return ""
    return " ".join(text.split())[:1200]


# ---------------------------------------------------------------- replies

def pick_reply(transcript):
    t = " " + transcript.lower().strip() + " "
    if any(w in t for w in (" emergency ", " ambulance ", " fire ", " police ")):
        return EMERGENCY
    if any(w in t for w in (" who is this ", " are you a robot ", " are you real ",
                            " are you human ", " is this an ai ", " automated ")):
        return identity()
    if (f" {OWNER.lower()} " in t
            or any(w in t for w in (" speak to ", " talk to ", " person ",
                                    " human ", " voicemail ", " call back "))):
        return unavailable()
    if any(w in t for w in (" message ", " tell him ", " pass on ", " let him know ")):
        return take_message()
    if any(w in t for w in (" goodbye ", " bye ", " that's all ", " thats all ")):
        return BYE
    return ack()


# ---------------------------------------------------------------- service

class Service:
    def __init__(self, args):
        self.args = args
        self.link = Link(self._line, self._pcm)
        self.audio = queue.Queue(maxsize=150)
        self.vad = Vad(threshold=args.vad_rms,
                       silence_ms=args.silence_ms,
                       min_speech_ms=args.min_speech_ms,
                       max_utterance_ms=args.max_utterance_ms)
        self.stt = Whisper(args.whisper, args.whisper_model,
                           threads=args.threads, timeout=args.stt_timeout)
        self.tts = Piper(args.piper, args.piper_model, timeout=args.tts_timeout,
                         raw_rate=args.piper_rate, raw_format=args.piper_raw)
        self.llm = Llm(url=args.llm_url, server=args.llama_server,
                       model=args.llm_model, port=args.llm_port,
                       threads=args.llm_threads or args.threads,
                       ctx=args.llm_ctx, timeout=args.llm_timeout)
        self.mode = args.mode
        self.facts = ""
        self.history = []
        self.intro_done = False
        self.finished = False
        self.turn = 0
        self.misses = 0
        self.listen_at = 0.0
        self.drop_until = 0.0
        self.stop = False
        self.handoff_sent = False
        self.message_sent = False
        self.ended_reason = "ended"

    def _line(self, line):
        if line == "END":
            self.intro_done = False
            self.finished = False
            self.turn = 0
            self.misses = 0
            self.listen_at = 0.0
            self.drop_until = 0.0
            self.handoff_sent = False
            self.message_sent = False
            self.history = []
            self.vad.reset()
            self.drain()
        elif line.startswith("CALL"):
            # A CALL is also a move to the other route: the call's state is
            # not reset, only what a new call starts with.
            self.finished = False
            self.ended_reason = "ended"
        elif line == "READY" and self.intro_done:
            self.listen_at = time.monotonic()

    # -- what the live monitor and the app see. Each line is best effort:
    #    a link that is gone must never stop the call.

    def _send_line(self, line):
        try:
            self.link.send_text(line)
        except (OSError, ConnectionError):
            pass

    def _state(self, name):
        self._send_line("STATE " + name)

    def _event(self, name, text=""):
        self._send_line(("EVENT %s %s" % (name, text)).strip())

    def _transcript(self, who, text):
        text = " ".join((text or "").split())[:500]
        if text:
            self._send_line("TRANSCRIPT %s %s" % (who, text))

    def _pcm(self, pcm, at):
        if not self.link.call_up:
            return
        try:
            self.audio.put_nowait((at, pcm))
        except queue.Full:
            try:
                self.audio.get_nowait()
            except queue.Empty:
                pass
            try:
                self.audio.put_nowait((at, pcm))
            except queue.Full:
                pass

    def drain(self):
        while True:
            try:
                self.audio.get_nowait()
            except queue.Empty:
                return

    def wait_ready(self, timeout=8.0):
        if self.link.ready:
            return True
        return self.link.ready_event.wait(timeout) and self.link.ready

    def say(self, text):
        if not self.link.call_up:
            return False
        if not self.wait_ready():
            log.warning("no READY for the line: %s", text)
            return False
        try:
            pcm = self.tts.synth(text)
        except Exception as e:
            log.error("tts: %s", e)
            return False
        log.info("ASSISTANT: %s", text)
        self._state("speaking")
        self._transcript("assistant", text)
        self.vad.reset()
        self.drain()
        self.link.played_event.clear()
        try:
            chunk = RATE // 50 * SAMPLE_BYTES  # 20 ms
            for off in range(0, len(pcm), chunk):
                if not self.link.call_up:
                    return False
                self.link.send_pcm(pcm[off:off + chunk])
            self.link.send_text("END_TURN")
        except OSError as e:
            log.warning("link: could not speak: %s", e)
            return False
        if not self.link.played_event.wait(self.args.play_timeout):
            log.warning("no PLAYED for the line")
        else:
            log.info("played %d ms", self.link.played_ms)
        self.drop_until = time.monotonic() + self.args.echo_gap
        self.vad.reset()
        self.drain()
        if self.link.call_up and not self.finished:
            self._state("listening")
        return True

    def hangup(self):
        try:
            self.link.send_text("HANGUP")
            log.info("asked the recorder to hang up")
        except OSError as e:
            log.warning("link: could not ask to hang up: %s", e)
        self.finished = True

    def finish(self, line=None, reason="closing"):
        if self.finished:
            return
        self.ended_reason = reason
        self._event("hungup", reason)
        self.say(line or closing())
        time.sleep(0.3)
        self.hangup()

    def load_facts(self):
        # Read again for each call: the owner may have edited the file since.
        self.facts = facts_for_prompt(self.args.facts) if self.mode == "assistant" else ""

    def system_prompt(self):
        prompt = (
            f"You are an automated phone assistant answering {OWNER}'s phone. "
            f"You are not {OWNER} and never pretend to be them. The call has "
            "already begun with a fixed line saying you are automated. "
            "Keep every reply to one or two short spoken sentences, under "
            "30 words. Ask who is calling and what the call is about if you "
            "do not know. Never give out personal information: no phone "
            "numbers, addresses, emails, passwords, financial details, or "
            f"details of {OWNER}'s location, schedule or contacts. Never make "
            "a commitment, promise, booking, payment or agreement. If the "
            f"caller asks for {OWNER} or a person, say they cannot come to the "
            "phone and offer to take a message. If the caller is in danger, "
            "tell them to hang up and call emergency services. Reply with "
            "speech only: no lists, markdown or emoji."
        )
        if self.mode == "assistant" and self.facts:
            prompt += (
                f" These are {OWNER}'s own notes for this call. Use them only "
                "to answer questions they actually answer, and do not "
                "invent anything beyond them: " + self.facts
            )
        elif self.mode == "assistant":
            prompt += (
                f" {owner_sentence()} left no notes. Take a message and say "
                "you will pass it on."
            )
        else:
            prompt += (
                " Take a short message: who is calling, why, and how to "
                "reach them."
            )
        return prompt

    def respond(self, text):
        if not self.llm.ready:
            return pick_reply(text)
        messages = self.history[-6:] + [{"role": "user", "content": text}]
        try:
            raw = self.llm.reply(self.system_prompt(), messages)
        except (urllib.error.URLError, OSError, ValueError, KeyError, IndexError) as e:
            log.error("llm: %s", e)
            return pick_reply(text)
        safe = sanitize_reply(raw)
        if safe is None:
            log.warning("blocked a reply: %s", " ".join((raw or "").split())[:200])
            self._event("blocked", "a reply was not safe to speak")
            return safe_fallback()
        self.history.append({"role": "user", "content": text})
        self.history.append({"role": "assistant", "content": safe})
        if len(self.history) > 8:
            self.history = self.history[-8:]
        return safe

    def handle(self, turn_pcm):
        samples = array.array("h")
        samples.frombytes(turn_pcm)
        if sys.byteorder != "little":
            samples.byteswap()
        log.info("turn: %.1f s, rms %d", len(samples) / RATE,
                 int(rms_of(samples)))
        self._state("thinking")
        text = self.stt.transcribe(turn_pcm)
        if not text:
            self.misses += 1
            log.info("CALLER: (nothing understood, miss %d)", self.misses)
            self._event("unsure", "nothing was understood")
            if self.misses >= self.args.max_misses:
                self.finish(closing(), "not understood")
            else:
                self.say(missed())
                self.listen_at = time.monotonic()
            return
        log.info("CALLER: %s", text)
        self._transcript("caller", text)
        self.misses = 0
        self.turn += 1
        limit = self.args.assistant_turns if self.mode == "assistant" else self.args.max_turns
        if self.turn >= limit:
            self.finish(closing(), "turn limit")
            return
        # The fixed lines are the safe ones: an emergency, a goodbye and a
        # request for the owner are never left to the model's wording.
        canned = pick_reply(text)
        if (not self.message_sent
                and any(w in (" " + text.lower() + " ") for w in
                        (" message ", " tell him ", " pass on ", " let him know ",
                         " call me at ", " my number "))) :
            self.message_sent = True
            self._event("message", "the caller is leaving a message")
        if wants_handoff(text) and not self.handoff_sent:
            self.handoff_sent = True
            self._event("handoff", f"the caller asked for {OWNER}")
            reply = handoff()
        elif canned in (EMERGENCY, BYE):
            reply = canned
        else:
            reply = self.respond(text)
        if reply in (EMERGENCY, BYE):
            # finish speaks it once, then asks the recorder to hang up.
            self.finish(reply, "closing")
            return
        self.say(reply)
        self.listen_at = time.monotonic()

    def check_timeouts(self):
        if self.finished or not self.intro_done or not self.link.call_up:
            return
        now = time.monotonic()
        if now - self.link.call_started_at > self.args.max_call:
            log.info("the call has run its limit")
            self._event("limit", "the call reached its time limit")
            self.finish(closing(), "time limit")
            return
        if not self.vad.in_speech and now - self.listen_at > self.args.no_speech:
            log.info("no speech for %d s", self.args.no_speech)
            self._event("silence", "the caller went quiet")
            self.finish(closing(), "silence")

    def run(self):
        self.link.start()
        while not self.stop:
            if not self.link.connected.wait(1.0):
                continue
            if not self.link.call_up:
                time.sleep(0.1)
                continue
            if not self.intro_done:
                if not self.link.ready:
                    time.sleep(0.1)
                    continue
                self.load_facts()
                self.history = []
                self.handoff_sent = False
                self.message_sent = False
                if not self.say(intro()):
                    # No line means a silent call: ask the recorder to end it
                    # rather than retrying into a caller who hears nothing.
                    log.error("the intro could not be spoken; asking to hang up")
                    self._event("error", "the assistant could not speak")
                    self.hangup()
                    continue
                self.intro_done = True
                self.turn = 0
                self.misses = 0
                self._event("answered", "the assistant answered")
                self._state("listening")
                self.listen_at = time.monotonic()
                self.vad.reset()
                self.drain()
                continue
            if self.finished:
                time.sleep(0.2)
                continue
            try:
                at, pcm = self.audio.get(timeout=0.2)
            except queue.Empty:
                self.check_timeouts()
                continue
            if at < self.drop_until:
                continue
            for turn_pcm in self.vad.feed_stereo(pcm):
                if self.finished:
                    break
                self.handle(turn_pcm)
                self.listen_at = time.monotonic()
            self.check_timeouts()


# ---------------------------------------------------------------- self-test

def tone(ms, amp=4000, freq=440):
    n = RATE * ms // 1000
    out = array.array("h", [0]) * (n * 2)
    for i in range(n):
        v = int(amp * math.sin(2 * math.pi * freq * i / RATE))
        out[i * 2] = v
        out[i * 2 + 1] = v
    return out.tobytes()


def silence(ms):
    return b"\x00" * (RATE * ms // 1000 * CHANNELS * SAMPLE_BYTES)


def selftest(args):
    ok = True

    def check(name, condition):
        nonlocal ok
        print(("ok   " if condition else "FAIL ") + name)
        ok = ok and bool(condition)

    # VAD: two tone turns, each ended by more than the silence gap.
    vad = Vad(threshold=args.vad_rms, silence_ms=args.silence_ms,
              min_speech_ms=args.min_speech_ms)
    turns = []
    for part in (silence(500), tone(900), silence(1000),
                 tone(600), silence(1000)):
        turns.extend(vad.feed_stereo(part))
    check("VAD ends two turns", len(turns) == 2)
    check("VAD keeps the speech", all(len(t) > RATE * 2 * 0.2 for t in turns))
    check("VAD ignores short clicks",
          not Vad(threshold=args.vad_rms).feed_stereo(silence(100) + tone(60) + silence(1200)))

    # The canned mapping is safe and identifies the assistant.
    check("the intro identifies the assistant", "automated assistant" in intro())
    check("a request for the owner is not a promise",
          pick_reply(f"Can I speak to {OWNER}?") == unavailable())
    check("an emergency gets the emergency line",
          pick_reply("This is an emergency") == EMERGENCY)
    check("a message gets the message line",
          pick_reply("I want to leave a message") == take_message())

    # The rails after the model: a line with personal data or a promise is
    # not spoken, and the fixed fallback is.
    check("a normal reply is kept",
          sanitize_reply("Sam is calling about the fence. Can I take a message?")
          == "Sam is calling about the fence. Can I take a message?")
    check("a phone number is blocked",
          sanitize_reply("Call him on 555 123 4567.") is None)
    check("a promise is blocked",
          sanitize_reply("I promise he will pay you.") is None)
    check("an email is blocked",
          sanitize_reply("Mail owner@example.com about it.") is None)
    check("a long reply is cut to two sentences",
          sanitize_reply("One. Two. Three.") == "One. Two.")
    check("an overlong reply is refused or cut",
          sanitize_reply("word " * 60) is None
          or len(sanitize_reply("word " * 60).split()) <= 45)
    check("a request for the owner is a handoff",
          wants_handoff(f"Can I speak to {OWNER}?"))
    previous = OWNER
    set_owner("Sam")
    check("a configured owner is used",
          "Sam" in intro() and wants_handoff("Can I speak to Sam?"))
    set_owner(previous)
    check("the prompt says it is automated",
          "automated phone assistant" in Service(args).system_prompt())

    # The local model's HTTP client, against a stand-in server: no model here.
    if not args.selftest_llm:
        from http.server import BaseHTTPRequestHandler, HTTPServer

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):  # noqa: N802 - http.server's name
                self.send_response(200)
                self.send_header("Content-Length", "2")
                self.end_headers()
                self.wfile.write(b"ok")

            def do_POST(self):  # noqa: N802 - http.server's name
                length = int(self.headers.get("Content-Length", 0))
                json.loads(self.rfile.read(length))
                out = json.dumps({"choices": [{"message": {
                    "content": "This is Sam about the fence."}}]}).encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(out)))
                self.end_headers()
                self.wfile.write(out)

            def log_message(self, *a):
                pass

        server = HTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            llm = Llm(url="http://127.0.0.1:%d" % server.server_address[1])
            check("the local model's answer is read",
                  sanitize_reply(llm.reply("system", [{"role": "user", "content": "hi"}]))
                  == "This is Sam about the fence.")
        finally:
            server.shutdown()

    # Piper, when it is here: a real line, converted to the link's format.
    if args.piper and args.piper_model and os.path.exists(args.piper_model):
        try:
            tts = Piper(args.piper, args.piper_model, raw_rate=args.piper_rate,
                        raw_format=args.piper_raw)
            pcm = tts.synth("This is a self test of the receptionist voice.")
            check("piper speaks", len(pcm) > RATE * SAMPLE_BYTES * 0.5)
            out = "selftest-reply.wav"
            with wave.open(out, "wb") as w:
                w.setnchannels(1)
                w.setsampwidth(2)
                w.setframerate(RATE)
                w.writeframes(pcm)
            print("wrote %s (48 kHz mono)" % out)
        except Exception as e:
            check("piper speaks (%s)" % e, False)
    else:
        print("skip piper: no model at %s" % args.piper_model)

    if args.whisper and os.path.exists(args.whisper_model):
        check("whisper is runnable", Whisper(args.whisper, args.whisper_model).available())
    else:
        print("skip whisper: no model at %s" % args.whisper_model)

    # The wrapper against a stand-in binary: the command it builds, and the
    # .txt it reads back, with no model.
    work = tempfile.mkdtemp(prefix="jemrec-selftest-")
    try:
        script = os.path.join(work, "whisper-cli")
        with open(script, "w", encoding="utf-8") as f:
            f.write("#!/bin/sh\nout=\n"
                    "while [ $# -gt 0 ]; do\n"
                    "  if [ \"$1\" = \"-of\" ]; then out=\"$2\"; shift 2; continue; fi\n"
                    "  shift\ndone\n"
                    "printf 'This is Sam about the fence.\\n' > \"$out.txt\"\n")
        os.chmod(script, 0o755)
        model = os.path.join(work, "model.bin")
        open(model, "wb").close()
        samples = array.array("h", [0]) * (RATE // 2)
        for i in range(len(samples)):
            samples[i] = int(3000 * math.sin(2 * math.pi * 440 * i / RATE))
        text = Whisper(script, model).transcribe(samples.tobytes())
        check("whisper's transcript is read", text == "This is Sam about the fence.")
    finally:
        shutil.rmtree(work, ignore_errors=True)

    print("SELFTEST " + ("OK" if ok else "FAILED"))
    return 0 if ok else 1


# ---------------------------------------------------------------- main

def default_paths(args):
    home = os.path.expanduser("~")
    if not args.whisper:
        args.whisper = (os.environ.get("JEMREC_WHISPER")
                        or shutil.which("whisper-cli")
                        or os.path.join(home, "whisper.cpp/build/bin/whisper-cli"))
    if not args.whisper_model:
        args.whisper_model = (os.environ.get("JEMREC_WHISPER_MODEL")
                              or os.path.join(home, "whisper.cpp/models/ggml-base.en.bin"))
    if not args.piper:
        args.piper = (os.environ.get("JEMREC_PIPER")
                      or shutil.which("piper")
                      or os.path.join(home, "piper/piper"))
    if not args.piper_model:
        args.piper_model = (os.environ.get("JEMREC_PIPER_MODEL")
                            or os.path.join(home, "piper/en_US-lessac-medium.onnx"))
    if not args.facts:
        args.facts = os.environ.get("JEMREC_FACTS") or os.path.join(home, "assistant.txt")
    if not args.llm_url:
        # The standard build locations; a URL says a server is already up.
        server = (args.llama_server or os.environ.get("JEMREC_LLAMA_SERVER")
                  or os.path.join(home, "llama.cpp/build/bin/llama-server"))
        model = (args.llm_model or os.environ.get("JEMREC_LLM_MODEL")
                 or os.path.join(home, "models/Llama-3.2-3B-Instruct-Q4_K_M.gguf"))
        if os.path.exists(server) and os.path.exists(model):
            args.llama_server = server
            args.llm_model = model
        else:
            args.llama_server = None
            args.llm_model = None
    if not args.log and not args.selftest:
        # The only record of what a caller said: no call is recorded, and
        # the canned lines promise the owner will hear about it.
        args.log = os.path.join(home, "receptionist.log")
    return args


def parse_args(argv):
    ap = argparse.ArgumentParser(description="JemRec's on-phone receptionist (M3).")
    ap.add_argument("--owner", default=os.environ.get("JEMREC_OWNER", DEFAULT_OWNER),
                    help="name the assistant answers for; default: the owner")
    ap.add_argument("--whisper", help="whisper.cpp binary")
    ap.add_argument("--whisper-model", help="ggml model")
    ap.add_argument("--piper", help="piper binary (a command line is allowed)")
    ap.add_argument("--piper-model", help="piper .onnx voice")
    ap.add_argument("--piper-rate", type=int, default=22050,
                    help="rate of a headerless piper output")
    ap.add_argument("--piper-raw", choices=("s16", "f32"), default="s16",
                    help="sample format of a headerless piper output")
    ap.add_argument("--threads", type=int, default=4, help="whisper threads")
    ap.add_argument("--stt-timeout", type=float, default=45)
    ap.add_argument("--tts-timeout", type=float, default=30)
    ap.add_argument("--play-timeout", type=float, default=45)
    ap.add_argument("--vad-rms", type=float, default=250)
    ap.add_argument("--silence-ms", type=int, default=900)
    ap.add_argument("--min-speech-ms", type=int, default=250)
    ap.add_argument("--max-utterance-ms", type=int, default=12000)
    ap.add_argument("--echo-gap", type=float, default=0.3)
    ap.add_argument("--no-speech", type=float, default=15)
    ap.add_argument("--max-turns", type=int, default=3)
    ap.add_argument("--max-misses", type=int, default=2)
    ap.add_argument("--max-call", type=float, default=110)
    ap.add_argument("--mode", choices=("receptionist", "assistant"),
                    default=os.environ.get("JEMREC_MODE", "receptionist"),
                    help="receptionist takes a message; assistant may use the notes")
    ap.add_argument("--facts", help="assistant mode's notes file")
    ap.add_argument("--assistant-turns", type=int, default=6)
    ap.add_argument("--llm-url", help="an already-running llama.cpp server")
    ap.add_argument("--llama-server", help="llama-server binary")
    ap.add_argument("--llm-model", help="GGUF model")
    ap.add_argument("--llm-port", type=int, default=8081)
    ap.add_argument("--llm-ctx", type=int, default=2048)
    ap.add_argument("--llm-threads", type=int, default=0,
                    help="llama-server threads; 0 uses --threads")
    ap.add_argument("--llm-timeout", type=float, default=25)
    ap.add_argument("--log", help="also append the transcript to this file")
    ap.add_argument("--no-wake-lock", action="store_true")
    ap.add_argument("--selftest", action="store_true")
    ap.add_argument("--selftest-llm", action="store_true",
                    help=argparse.SUPPRESS)
    return ap.parse_args(argv)


def main(argv=None):
    args = default_paths(parse_args(argv or sys.argv[1:]))
    set_owner(args.owner)
    handlers = [logging.StreamHandler(sys.stdout)]
    if args.log:
        handlers.append(logging.FileHandler(args.log))
    logging.basicConfig(level=logging.INFO, handlers=handlers,
                        format="%(asctime)s %(message)s", datefmt="%H:%M:%S")

    if args.selftest:
        return selftest(args)

    stt = Whisper(args.whisper, args.whisper_model, timeout=args.stt_timeout)
    if not stt.available():
        log.error("whisper.cpp is not here: %s or %s", args.whisper, args.whisper_model)
        log.error("build it with docs/AI-SERVICE.md, or pass --whisper and --whisper-model")
        return 2
    tts = Piper(args.piper, args.piper_model, timeout=args.tts_timeout,
                raw_rate=args.piper_rate, raw_format=args.piper_raw)
    if not tts.available():
        log.error("piper is not here: %s or %s", args.piper, args.piper_model)
        log.error("install it with docs/AI-SERVICE.md, or pass --piper and --piper-model")
        return 2

    lock = False
    if not args.no_wake_lock and shutil.which("termux-wake-lock"):
        subprocess.run(["termux-wake-lock"], check=False)
        lock = True
        atexit.register(lambda: subprocess.run(["termux-wake-unlock"], check=False))

    service = Service(args)

    def stop(_signum, _frame):
        service.stop = True
        service.link.close()

    signal.signal(signal.SIGINT, stop)
    signal.signal(signal.SIGTERM, stop)
    if service.llm.available():
        if not service.llm.start():
            log.error("the local model did not start; canned replies for this run")
    else:
        log.warning("no local model configured (see docs/AI-SERVICE.md); canned replies for this run")
    log.info("receptionist listening; whisper=%s piper=%s mode=%s llm=%s",
             args.whisper, args.piper, args.mode, service.llm.url or "canned")
    try:
        service.run()
    finally:
        service.link.close()
        service.llm.stop()
        if lock:
            subprocess.run(["termux-wake-unlock"], check=False)
    return 0


if __name__ == "__main__":
    sys.exit(main())
