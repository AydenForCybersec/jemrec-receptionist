# The AI receptionist fork: the live link

This fork turns JemRec's answering machine into an on-phone AI receptionist.
The recorder keeps the one thing that makes it possible: it runs as the shell,
answers the call, mutes the microphone, and speaks into the caller's uplink
through the route the call takes (in-call music, or the call-screening track
for a call through the mixer). What the fork adds is a live link over which an
assistant on the phone hears the call and speaks into it, plus a monitor
socket so you can follow and take over.

Status: M0 and the M1 fixed-clip call passed on the S24 FE. M2 (Whisper,
Piper, canned replies) and M3 (local llama.cpp model, safety rails, assistant
mode, live monitor, decision notices) are built and tested off the phone. The
real call with the model is the device test. The UI cut (the three looks,
recordings library and player) is the one open piece; nothing on the call path
depends on it.

## What this build does

- `AnswerSettings` takes a fourth field: `1,120,music,ai`. Without it the
  answering machine takes a message as before. With it the machine answers,
  mutes, opens the assistant's track and sends nothing from the greeting file.
- The app in this fork always appends `,ai`, so every call it answers is the
  assistant's. A recorded greeting is not needed: the assistant speaks its own
  first line. The greeting controls stay as an optional test voice; the test
  call plays the check tone and beep whether or not one is recorded, and never
  starts the assistant itself.
- `AiLink` carries the call: one duplex socket on the recorder's port.
- `AssistantAudio` keeps one injection track open for the whole call, writes
  1.5 seconds of silence first and reports `READY` when that has played, and
  writes silence between lines so the route never drops.
- `CallAudioPump` captures the call for the assistant with no file and no
  encoder. AI mode writes no recordings at all: the recording mode is not
  consulted, and the capture starts when the assistant takes the call, not at
  the off-hook.
- The app is woken with `AI_STARTED` and `AI_ENDED`, and its call service
  says "Assistant answering" for the length of the call. It holds no
  recording and puts up no Record prompt.

## The link

The app connects to the recorder as it always does: the handshake in
`Handshake`, with the token the app generated, then a command. The assistant
cannot, because it is Termux and has no token, so the AI link is a command of
its own (`a`) checked by the kernel instead: the recorder reads the uid owning
the client's socket from `/proc/net/tcp` and admits only the shell and the
app named by `Main.AI_PACKAGE` (`com.termux`, or `JEMREC_AI_PACKAGE`). That is
the same table the recorder already uses to refuse every other caller. No
secret is written anywhere, and an app that is not Termux gets nothing.

The app's live monitor uses the ordinary handshake and the `m` command: it
hears the call and can say nothing. One monitor per call is the normal case;
the recorder can carry more, and replays the call's lines to one that came
late.

Frames, both ways (`AiFrames`):

```
1 byte type   1 = PCM, 2 = UTF-8 text
4 bytes       big-endian length, at most 1 MiB
n bytes       payload
```

PCM out is the capture as recorded: 48 kHz stereo, 16-bit little-endian. PCM
in is the track's format: 48 kHz mono, 16-bit little-endian. Control lines:

| From the recorder | Meaning |
| --- | --- |
| `HELLO 1` | The link is up. |
| `CALL path=voice route=music` | A call is the assistant's; the route is the one its track uses. Sent again with a new route if the call moves. |
| `READY` | The 1.5 s lead-in has played. Speak now, or the caller loses the first words. |
| `PLAYED <ms>` | Answer to `END_TURN`: that much audio has played. |
| `END` | The call is over. |
| `ERROR audio <why>` | The track failed; the recorder will end the call by its limit if nothing else does. |

| To the recorder | Meaning |
| --- | --- |
| PCM frames | Speech for the call's uplink. |
| `END_TURN` | All of this line is sent. The recorder waits for it to play and answers `PLAYED`. |
| `HANGUP` | End the call, after the closing line has played. |
| `PING` | Answers `PONG`. |
| `STATUS` | Answers the current `CALL` line, or `IDLE`. |
| `STATE <name>` | What the assistant is doing: `starting`, `intro`, `listening`, `thinking`, `speaking`, `ended`. For the app. |
| `TRANSCRIPT <who> <words>` | One said line: `who` is `caller` or `assistant`. For the app's live screen and the call notice. |
| `EVENT <name> [detail]` | A decision: `answered`, `handoff`, `unsure`, `blocked`, `hungup`, `limit`, `silence`. For the app's notice and the after-call report. |

The recorder passes `STATE`, `TRANSCRIPT` and `EVENT` to every monitor
session and keeps the last 60 for a monitor that connects mid-call. They go
nowhere else: no file is written from them, and the call's audio is not
recorded.

`READY` is per call and per route: after a call moves, wait for the new
`READY` before the next line.

## In the app

- The call service opens the monitor when `AI_STARTED` arrives and closes it
  on `AI_ENDED`. It reconnects while the call is up.
- The ongoing notification is the decision report, in place: "Saying hello",
  "Listening to the caller", "Working out a reply", "Speaking to the caller",
  "The caller asked for you", each with your microphone muted. The caller's
  last words are the notification's subtext. Tapping it opens the live screen.
- The live screen (`Screen.AI_MONITOR`) shows the state, the transcript as it
  arrives, a Listen switch that plays the caller's side through this phone,
  and Talk to them / Hang up. Talk to them is the recorder's `HANDOVER`; it
  stops the assistant, unmutes, and the call is yours.
- When the call ends, a short "AI call ended" notice gives the reason and the
  number of caller turns; tapping it reopens the transcript.
- Answering machine, while a call is up: "AI call in progress" opens the same
  screen.

## The fixed-clip test (M1)

No AI: a WAV is the whole "assistant". Useful when the model or Termux is
suspect.

1. Build and install this fork. The debug APK is signed with the build
   machine's debug key, so uninstall any JemRec already on the phone first;
   the pairing is done again in setup.
2. Finish setup, turn the answering machine on. No greeting is needed.
3. Put `tools/ai/clip_link.py` and a 16-bit WAV clip on the phone.
4. In Termux, with the answering machine on, run:

   ```bash
   python clip_link.py --clip m1-test-clip.wav --caller caller.wav
   ```

   It prints `HELLO`, then `CALL`, waits for `READY`, sends the clip, asks for
   `END_TURN`, and prints `PLAYED`.
5. Call the phone from another phone and let it ring past the answer delay.
   The caller should hear the clip, in full, after about 1.5 s of silence.
   The phone should show "Assistant answering" and the live screen, and the
   recordings list should gain nothing when it ends.

## Safety rails

- The first line is fixed and says "automated assistant". The model never
  writes it.
- Every model reply is checked before it is spoken: no phone numbers, emails,
  URLs, addresses, passwords, financial details, or commitment language
  (`I promise`, `I'll pay`, `I'll book`, `I'll sign`, and the like). A blocked
  reply becomes the fixed fallback line and an `EVENT blocked`.
- The system prompt forbids pretending to be the owner, giving personal
  details, and making commitments. The post-check is the rail, not the prompt.
- A request for the owner or a person gets the fixed handoff line and `EVENT
  handoff`; the caller is not told the owner is available.
- An emergency gets the fixed "hang up and call emergency services" line.
- The recorder caps the call at your longest setting (2 minutes for a
  conversation) and the service closes after its turn limit.

## Assistant mode

`receptionist.py --mode assistant` gives the model your `assistant.txt`
(your own notes, capped at 1,200 characters) and six caller turns. Without it
the service takes a message and does not answer questions about you. The
facts file is read again at the start of every call. See AI-SERVICE.md.

## What is not here yet

- The UI cut: the three looks, the recordings library and player are still
  present, with nothing new to play from the assistant's calls. They are not
  on the call path.
- The full model conversation on the S24 FE: the code is tested against a
  stand-in server here, not against a real 3B model on the phone.

## Testing

Daemon decisions are plain Java and run off the phone:

```
./gradlew :daemon-tests:test :daemon-tests:errorProne
```

The app's tests: `AiLiveTest`, `CallNoticeTest`, `AnsweringMachineTest`,
`AnswerSummaryTest`, and `./gradlew lint`. The Termux service's own checks:

```
python receptionist.py --selftest
python selftest_loop.py
```
