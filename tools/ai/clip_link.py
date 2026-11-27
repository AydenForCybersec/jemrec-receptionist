#!/usr/bin/env python3
"""M1's fixed-clip client for the daemon's AI link, run in Termux.

It connects to the recorder on 127.0.0.1:28472 as the Termux app, waits for
READY (the call's 1.5 s lead-in), sends one WAV clip to the call's uplink,
waits for PLAYED, and saves the caller's side as caller.wav. No speech
recognition, no model: this proves the audio path on a real call.

    pkg install python
    python clip_link.py --clip greeting.wav --hangup

The clip must be 16-bit PCM; mono is expected, and a stereo file is mixed
down. 48 kHz is used as it is; other rates are resampled linearly, which is
enough for a test tone or a spoken line. Everything stays on the phone: the
socket is loopback and the files are in the current directory.
"""

import argparse
import socket
import struct
import sys
import time
import wave

HOST = "127.0.0.1"
PORT = 28472
TYPE_PCM = 1
TYPE_TEXT = 2
MAX_PAYLOAD = 1 << 20
RATE = 48000


def read_exact(sock, n):
    buf = bytearray()
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            return None
        buf.extend(chunk)
    return bytes(buf)


def read_frame(sock):
    head = read_exact(sock, 5)
    if head is None:
        return None
    kind = head[0]
    (length,) = struct.unpack(">I", head[1:])
    if length > MAX_PAYLOAD:
        raise ValueError("frame too large: %d" % length)
    payload = read_exact(sock, length) if length else b""
    if payload is None:
        return None
    return kind, payload


def send_frame(sock, kind, payload):
    sock.sendall(struct.pack(">BI", kind, len(payload)) + payload)


def send_text(sock, line):
    send_frame(sock, TYPE_TEXT, line.encode("utf-8"))


def load_clip(path):
    with wave.open(path, "rb") as w:
        if w.getsampwidth() != 2:
            raise SystemExit("clip must be 16-bit PCM, not %d bytes per sample" % w.getsampwidth())
        channels = w.getnchannels()
        rate = w.getframerate()
        frames = w.readframes(w.getnframes())
    samples = list(struct.unpack("<%dh" % (len(frames) // 2), frames))
    if channels == 2:
        samples = [(samples[i] + samples[i + 1]) // 2 for i in range(0, len(samples), 2)]
    elif channels != 1:
        raise SystemExit("clip must be mono or stereo")
    if rate != RATE:
        # Linear is enough for a test, and keeps this to the standard library.
        out = []
        step = rate / RATE
        i = 0.0
        while int(i) < len(samples):
            j = int(i)
            frac = i - j
            nxt = samples[j + 1] if j + 1 < len(samples) else samples[j]
            out.append(int(samples[j] + (nxt - samples[j]) * frac))
            i += step
        samples = out
        print("clip resampled %d -> %d Hz" % (rate, RATE), flush=True)
    return struct.pack("<%dh" % len(samples), *samples)


def save_caller(path, chunks):
    with wave.open(path, "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(b"".join(chunks))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--clip", required=True, help="16-bit PCM WAV to speak to the caller")
    ap.add_argument("--second", help="optional second WAV, after the first")
    ap.add_argument("--caller", default="caller.wav", help="where to save the caller's side")
    ap.add_argument("--hangup", action="store_true", help="hang up after speaking")
    ap.add_argument("--wait", type=float, default=90.0, help="seconds to watch the call")
    args = ap.parse_args()

    first = load_clip(args.clip)
    second = load_clip(args.second) if args.second else None

    sock = socket.create_connection((HOST, PORT), timeout=10)
    sock.settimeout(1.0)
    sock.sendall(b"a")
    print("connected; waiting for the call and READY", flush=True)

    caller = []
    ready = False
    spoke = False
    deadline = time.time() + args.wait
    try:
        while time.time() < deadline:
            try:
                frame = read_frame(sock)
            except socket.timeout:
                continue
            if frame is None:
                print("the link closed", flush=True)
                break
            kind, payload = frame
            if kind == TYPE_TEXT:
                line = payload.decode("utf-8", "replace").strip()
                print("%8.2f  %s" % (time.time() % 1000, line), flush=True)
                if line == "READY":
                    ready = True
                    send_frame(sock, TYPE_PCM, first)
                    send_text(sock, "END_TURN")
                    spoke = True
                elif line.startswith("PLAYED") and spoke and second is not None:
                    send_frame(sock, TYPE_PCM, second)
                    send_text(sock, "END_TURN")
                    second = None
                elif line == "END" or line == "BYE":
                    print("the call ended", flush=True)
                    break
            elif kind == TYPE_PCM:
                caller.append(payload)
                if ready:
                    print(".", end="", flush=True)
        if args.hangup:
            send_text(sock, "HANGUP")
            print("\nasked to hang up", flush=True)
            time.sleep(1.0)
    finally:
        save_caller(args.caller, caller)
        sock.close()
        print("\nsaved %s (%.1f s of the caller)" % (args.caller, sum(len(c) for c in caller) / (RATE * 4)), flush=True)


if __name__ == "__main__":
    main()
