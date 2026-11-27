# The Termux receptionist (M3)

The service that makes the assistant talk. It runs in Termux, connects to the
recorder's AI link on loopback, hears the caller, finds the end of each turn,
transcribes it with whisper.cpp, answers it with a local llama.cpp model,
speaks the reply with Piper, and asks the recorder to hang up. Nothing leaves
the phone and no account is used.

M3 adds the local model, the safety rails, assistant mode and the live
transcript. The first line of every call is fixed and says the assistant is
automated; the model is never asked to introduce itself.

## What a call sounds like

1. The recorder answers, mutes the microphone and opens the assistant's track.
2. The service waits for `READY` (the 1.5 s lead-in), then speaks:
   "Hi, this is an automated assistant answering for the owner. Who is
   calling, and what is this about?"
   The name is whatever `--owner` or `JEMREC_OWNER` sets.
3. It listens. When the caller has been quiet for 900 ms, the turn is over.
4. Whisper transcribes it, the local model writes a short reply (or a canned
   line is used when no model is configured), the reply is checked against the
   safety rails, Piper speaks it, and the service waits until the line has
   played before listening again.
5. After three caller turns (six in assistant mode), 15 s without speech, or
   the recorder's time limit, it says the closing line and asks the recorder to
   hang up.

Every caller line and every assistant line is in the log
(`~/receptionist.log` by default). That log is the message: this fork records
no call audio. The same lines go to the app's live monitor as `TRANSCRIPT`
frames, and the assistant's step as `STATE` frames.

## Install (one time)

Termux from F-Droid, not the Play Store version.

```bash
pkg update -y && pkg upgrade -y
pkg install -y python git cmake clang make curl
```

### Whisper

```bash
git clone --depth 1 https://github.com/ggerganov/whisper.cpp ~/whisper.cpp
cd ~/whisper.cpp
cmake -S . -B build -DGGML_NO_OPENMP=ON
cmake --build build -j"$(nproc)"
bash ./models/download-ggml-model.sh base.en
```

On a phone, `base.en` is the speed choice; `small.en` is more accurate and
slower. Test the binary before involving the service:

```bash
cd ~/whisper.cpp
./build/bin/whisper-cli -m models/ggml-base.en.bin -f samples/jfk.wav -nt
```

### Piper

Use the Termux build of Piper. It is a `.deb` from
`github.com/gyroing/piper-tts-for-termux` (releases); install it with
`apt install -f ./piper-tts-cli-*.deb`, and install `espeak-ng` if it asks
for it. Put `en_US-lessac-medium.onnx` and its `.onnx.json` in a folder and
point `PIPER_VOICE_PATH` at it. That build takes the voice's name, not its
path, and writes headerless audio; the service reads it.

If `pip install piper-tts` works in your Termux, the official CLI is also
fine, and writes a WAV:

```bash
python -m venv ~/piper-venv
source ~/piper-venv/bin/activate
pip install piper-tts
python -m piper.download_voices en_US-lessac-medium
```

The service checks for a WAV header; if the file has none, it reads 16-bit
samples at `--piper-rate` (22050 by default). If the Termux build writes
32-bit float, add `--piper-raw f32`.

### The local model

llama.cpp with a 3B dense Q4 model: Llama 3.2 3B Instruct, Qwen2.5 3B
Instruct, or similar. No bigger on an 8 GB phone.

```bash
git clone --depth 1 https://github.com/ggml-org/llama.cpp ~/llama.cpp
cd ~/llama.cpp
cmake -S . -B build -DGGML_OPENMP=OFF
cmake --build build -j"$(nproc)" --target llama-server
mkdir -p ~/models
curl -L --fail -o ~/models/Llama-3.2-3B-Instruct-Q4_K_M.gguf \
  https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf
```

Test it before involving the service. The model loads for a while; the service
waits for it.

```bash
cd ~/llama.cpp
./build/bin/llama-server -m ~/models/Llama-3.2-3B-Instruct-Q4_K_M.gguf \
  -c 2048 --host 127.0.0.1 --port 8081 -t 4
```

The service looks for `~/llama.cpp/build/bin/llama-server` and
`~/models/Llama-3.2-3B-Instruct-Q4_K_M.gguf` by itself. Other paths:
`--llama-server`, `--llm-model`, or `JEMREC_LLAMA_SERVER` and
`JEMREC_LLM_MODEL`. If a server is already running, pass `--llm-url
http://127.0.0.1:8081` and the service will not start one of its own.

### The service

Copy `tools/ai/receptionist.py` from this repo to the phone, into
`~/receptionist/`. From the repository root on a computer with ADB:

```bash
adb push tools/ai/receptionist.py /sdcard/Download/
```

Then in Termux:

```bash
termux-setup-storage
mkdir -p ~/receptionist
cp /sdcard/Download/receptionist.py ~/receptionist/
```

Set who the assistant answers for. Without this it says "the owner", which is
correct but impersonal. Keep your real name out of the repository and set it
only on the phone:

```bash
export JEMREC_OWNER="Your Name"
# or pass --owner "Your Name" to receptionist.py
```

Set the paths (put these in `~/.bashrc` if you like):

```bash
export JEMREC_WHISPER=$HOME/whisper.cpp/build/bin/whisper-cli
export JEMREC_WHISPER_MODEL=$HOME/whisper.cpp/models/ggml-base.en.bin
export JEMREC_PIPER=$HOME/piper/piper
export JEMREC_PIPER_MODEL=$HOME/piper/en_US-lessac-medium.onnx
```

For the Termux Piper package, the last two may instead be:

```bash
export PIPER_VOICE_PATH=$HOME/piper/voices
export JEMREC_PIPER=piper
export JEMREC_PIPER_MODEL=en_US-lessac-medium
```

## Test the pipeline, then run it

The self-test needs no call and no phone line. It checks the turn detector,
the canned lines, the reply rails (a phone number, an email and a promise are
refused), Piper when present, and the Whisper and llama.cpp wrappers against
stand-in binaries/servers:

```bash
cd ~/receptionist
python receptionist.py --selftest
```

It writes `selftest-reply.wav`, 48 kHz mono, if Piper is found. Then start the
service:

```bash
termux-wake-lock
python receptionist.py
```

Leave that session running, or run it with `nohup` and read the log. The
service reconnects every two seconds while the recorder is down, so it can
start before or after the daemon. It starts `llama-server` itself when it
finds one and waits for it to load; the log says `llm: ready at ...`.

On the phone:

- JemRec's answering machine is on and its checks pass. No greeting needs to
  be recorded in this fork: the assistant speaks its own first line.
- The app's Test call works without a greeting and without this service: it
  plays a check tone and beep on the same route, so it tests the phone's
  audio path, not the model. Use a real call to test the assistant.
- Set the longest call to 2 minutes. The app's default is 60 s; a real
  conversation needs the 120 s setting.
- Termux is set to Unrestricted battery use in Android settings, and the wake
  lock is held. Without both, One UI may freeze Termux while the call runs.
- The app should say "Assistant answering" for the call; no recording is
  written. Tapping that notice opens the live transcript.

Call the phone from another phone:

1. "Hi, this is Sam about the fence." The assistant acknowledges and asks
   about a message.
2. "Can I speak to the owner?" It says the owner cannot come to the phone,
   offers to take a message, and the app's notice says the caller asked for
   you.
3. "That's all, goodbye." It closes and hangs up.

The log shows `CALL`, `READY`, `CALLER:`, `ASSISTANT:` and `PLAYED` lines.
The app's live screen shows the same lines as they happen, and can play the
caller's side while Listen is on.

## Assistant mode

`--mode assistant` keeps the call longer (six turns) and lets the model answer
from the owner's own notes:

```bash
cat > ~/assistant.txt <<'EOF'
I'm at work until 6 today. The fence repair is booked for Friday morning.
I can't take calls about the invoice until Monday.
EOF
python receptionist.py --mode assistant
```

The notes are read again at the start of each call, capped at 1,200
characters, and put in the model's prompt. Keep secrets out of them: the model
may repeat anything in the file. Without the flag the service stays in
receptionist mode and takes a message; it does not answer questions about the
owner.

A request for the owner, a person, or a call back is a handoff: the service
sends `EVENT handoff`, the app's notice says so, and you can tap it and choose
Talk to them while the call is still up.

## Tuning

- `--vad-rms 250`: the sound level that counts as speech. The log prints the
  RMS of each turn; lower it for a quiet caller, raise it in a noisy room.
- `--silence-ms 900`: the pause that ends a turn.
- `--no-speech 15`: silence after the intro before the closing line.
- `--max-turns 3`: caller turns before the closing line; `--assistant-turns 6`
  in assistant mode.
- `--echo-gap 0.3`: capture discarded after a line has played, so the service
  does not hear itself. Raise it if it starts answering its own voice.
- `--threads 4`: Whisper threads; `--llm-threads` defaults to the same.
- `--llm-ctx 2048`: the model's context. A longer call keeps only the last
  three exchanges, so this is plenty.
- `--llm-timeout 25`: how long one reply may take; a timeout falls back to a
  canned line, never to silence.
- `--max-call 110`: the service's own backstop; the recorder's limit is the
  one that hangs up at 2 minutes.

## Safety

- The first line is fixed: "this is an automated assistant answering for
  the owner". The model cannot change it, and the configured owner name is
  inserted by the service.
- A reply containing a phone number, an email, a URL, an address, a password,
  a financial detail, or a promise, booking, payment or agreement is not
  spoken. The fixed fallback is said instead, and the event `blocked` is sent.
- The prompt forbids pretending to be the owner, giving personal details,
  and making commitments. The post-check is the rail; the prompt is not
  trusted.
- An emergency gets the fixed line: hang up and call emergency services.
- Every call is capped by the recorder at your setting (2 minutes for a
  conversation), and closes after the last turn. The transcript is the record;
  no audio is saved.

## Limits

- A 3B model on an Exynos 2400 takes a few seconds per reply. The caller
  hears a pause; the service speaks as soon as the line is ready.
- The model can be wrong. The rails stop the dangerous categories, not every
  odd sentence; the fixed fallback is the safe line.
- The transcript on the phone is in memory and in `~/receptionist.log`. The
  app's screen shows the current call; it is not a history.
- If the app is frozen, the call still runs in the recorder and Termux. The
  notification and monitor can arrive late; the recorder's limit still ends
  the call.
- No actions: no texts, no calendar, no bookings. The assistant only talks.

## If something is wrong

- `Connection refused`: the recorder is not running or the app is not paired.
  The service retries; start the call only after `connected` is in the log.
- No `CALL`: the call was not answered by the assistant. Check the answering
  machine is on and its checks pass; no greeting is needed in this fork.
- No `READY`: the injection track failed. The daemon log says which route.
- Whisper errors: run its command by hand on a WAV; check the model path.
- Piper errors: run `piper -m ... -f /tmp/t.wav` by hand. If the file has no
  RIFF header, it is raw; if it sounds like noise, try `--piper-raw f32`.
- The model does not load: read `$TMPDIR/jemrec-llama.log`. The service says
  `the local model did not start; canned replies for this run` and carries on
  with the fixed lines, so a call still works.
- The caller hears nothing after the intro: check `PLAYED` in the log and the
  daemon's `ai:` lines. A `PLAYED 0` means the track was gone.
- The service answers itself: raise `--echo-gap`, and check that the phone is
  not mixing the injection into the capture.
- The app shows no transcript: the recorder's `m` monitor link or the app's
  service was stopped. The call goes on; the log still has everything.
