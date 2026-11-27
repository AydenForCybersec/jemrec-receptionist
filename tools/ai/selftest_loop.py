#!/usr/bin/env python3
"""A simulated call for receptionist.py: no phone, no models.

It starts a loopback stand-in for the daemon's AI link, runs the service with
a fake transcriber and a fake voice, and drives three caller turns through it:

    python selftest_loop.py

Checks that the service waits for READY, speaks an intro, answers each turn,
says the closing line and asks the daemon to hang up. The real audio, Whisper
and Piper are exercised on the phone.
"""

import array
import math
import socket
import struct
import sys
import threading
import time

import receptionist as r

PORT = 28573
RATE = 48000


def frame(kind, payload):
    return struct.pack(">BI", kind, len(payload)) + payload


def read_frame(conn):
    head = b""
    while len(head) < 5:
        chunk = conn.recv(5 - len(head))
        if not chunk:
            return None
        head += chunk
    kind = head[0]
    (length,) = struct.unpack(">I", head[1:])
    payload = b""
    while len(payload) < length:
        chunk = conn.recv(length - len(payload))
        if not chunk:
            return None
        payload += chunk
    return kind, payload


def tone(ms, amp=6000):
    n = RATE * ms // 1000
    out = array.array("h", [0]) * (n * 2)
    for i in range(n):
        v = int(amp * math.sin(2 * math.pi * 440 * i / RATE))
        out[i * 2] = v
        out[i * 2 + 1] = v
    return out.tobytes()


def quiet(ms):
    return b"\x00" * (RATE * ms // 1000 * 4)


class FakeStt:
    def transcribe(self, pcm):
        return "This is Sam about the fence."


class FakeTts:
    def synth(self, text):
        return quiet(300)


class Server(threading.Thread):
    def __init__(self):
        super().__init__(daemon=True)
        self.ready = threading.Event()
        self.done = threading.Event()
        self.errors = []
        self.ends_turns = 0
        self.hangup = False
        self.sock = None

    def run(self):
        s = socket.socket()
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        s.bind(("127.0.0.1", PORT))
        s.listen(1)
        s.settimeout(10)
        self.sock = s
        self.ready.set()
        try:
            conn, _ = s.accept()
            conn.settimeout(20)
            if conn.recv(1) != b"a":
                self.errors.append("no AI byte")
                return
            conn.sendall(frame(r.TYPE_TEXT, b"HELLO 1"))
            conn.sendall(frame(r.TYPE_TEXT, b"CALL path=voice route=music"))
            conn.sendall(frame(r.TYPE_TEXT, b"READY"))
            # Intro.
            if not self._wait_end(conn):
                return
            for i in range(3):
                time.sleep(1.2)
                conn.sendall(frame(r.TYPE_PCM, quiet(200)))
                conn.sendall(frame(r.TYPE_PCM, tone(1200)))
                conn.sendall(frame(r.TYPE_PCM, quiet(1200)))
                if not self._wait_end(conn):
                    return
            # The last END_TURN is the closing line; the HANGUP follows it.
            self._wait_hangup(conn, 5)
        except Exception as e:  # noqa: BLE001 - reported, not raised
            self.errors.append(repr(e))
        finally:
            s.close()

    def _wait_hangup(self, conn, seconds):
        deadline = time.time() + seconds
        while time.time() < deadline:
            try:
                item = read_frame(conn)
            except socket.timeout:
                return
            if item is None:
                return
            kind, payload = item
            if kind == r.TYPE_TEXT:
                line = payload.decode()
                if line == "HANGUP":
                    self.hangup = True
                    conn.sendall(frame(r.TYPE_TEXT, b"BYE"))
                    conn.sendall(frame(r.TYPE_TEXT, b"END"))
                    self.done.set()
                    return

    def _wait_end(self, conn):
        while True:
            item = read_frame(conn)
            if item is None:
                self.errors.append("link closed while waiting for END_TURN")
                return False
            kind, payload = item
            if kind == r.TYPE_TEXT:
                line = payload.decode()
                if line == "END_TURN":
                    self.ends_turns += 1
                    conn.sendall(frame(r.TYPE_TEXT, b"PLAYED 300"))
                    return True
                if line == "HANGUP":
                    self.hangup = True
                    conn.sendall(frame(r.TYPE_TEXT, b"BYE"))
                    conn.sendall(frame(r.TYPE_TEXT, b"END"))
                    self.done.set()
                    return True
            # PCM from the service is counted, not inspected.


def main():
    server = Server()
    server.start()
    if not server.ready.wait(5):
        print("FAIL server did not start")
        return 1
    r.PORT = PORT
    args = r.default_paths(r.parse_args(["--no-wake-lock", "--silence-ms", "600",
                                           "--max-turns", "3", "--no-speech", "20"]))
    service = r.Service(args)
    service.stt = FakeStt()
    service.tts = FakeTts()
    worker = threading.Thread(target=service.run, daemon=True)
    worker.start()
    server.done.wait(20)
    service.stop = True
    service.link.close()
    worker.join(3)
    ok = True

    def check(name, condition):
        nonlocal ok
        print(("ok   " if condition else "FAIL ") + name)
        ok = ok and bool(condition)

    check("the service spoke an intro", server.ends_turns >= 1)
    check("the service answered caller turns", server.ends_turns >= 3)
    check("the service asked to hang up", server.hangup)
    check("no link errors", not server.errors)
    if server.errors:
        for e in server.errors:
            print("   ", e)
    print("LOOP " + ("OK" if ok else "FAILED"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
