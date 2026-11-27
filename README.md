# JemRec Receptionist

An on-phone AI receptionist for unrooted Android, built as a fork of
[JemRec](https://github.com/jemcik/JemRec).

It answers calls you do not take, says it is automated, has a short
conversation, shows you a live transcript, and lets you take over. Speech
recognition, the reply model and the voice all run on the phone in Termux. No
cloud, no account, and no call recording on assistant calls.

> **Experimental fork.** This is not the official JemRec app and is not
> affiliated with JemRec, Jemcik or F-Droid. It answers ordinary phone calls
> only, not WhatsApp, Telegram, Signal, Viber or Google Meet calls. Do not
> install it over the official app: the debug build uses a different signing
> key. Read [Status](#status) before relying on it.

## Status

The call path, live monitor, local service and safety rails are built. The
fixed-clip call passed on a Galaxy S24 FE. The real model call on the phone is
still the device test, so treat this as experimental.

The base recorder still contains the upstream UI cut: the three looks, the
recordings library and the player. Nothing on the assistant call path depends
on that work.

## Download

The [1.9 arm64 preview](https://github.com/AydenForCybersec/jemrec-receptionist/releases/tag/preview-arm64-1.9)
has a prebuilt debug APK:

- [jemrec-receptionist-1.9-arm64-debug.apk](https://github.com/AydenForCybersec/jemrec-receptionist/releases/download/preview-arm64-1.9/jemrec-receptionist-1.9-arm64-debug.apk)
- [SHA-256 checksum](https://github.com/AydenForCybersec/jemrec-receptionist/releases/download/preview-arm64-1.9/jemrec-receptionist-1.9-arm64-debug.apk.sha256)

It is debug-signed and built from commit `9a94834`. It contains `arm64-v8a`
and `armeabi-v7a` libraries, not x86. Android 12 or newer. Uninstall the
official JemRec or any differently signed fork before installing. This is the
same experimental build described below.

## What a call does

1. JemRec answers a call you did not take, mutes your microphone and opens an
   audio track into the caller's uplink.
2. A Python service in Termux hears the caller, finds the end of each turn,
   transcribes it with whisper.cpp, writes a short reply with a local
   llama.cpp model, and speaks it with Piper.
3. The app shows the live state and transcript. You can listen to the caller
   and tap **Talk to them** at any time.
4. The call is capped by the app's longest-call setting; set it to 120 seconds
   for a two-minute conversation. The assistant calls no tools, makes no
   bookings or promises, and does not record the call. The transcript is the
   message.

The first line is fixed and says the assistant is automated. A request for
you, an emergency, or a goodbye uses a fixed safe line. Every model reply is
checked for phone numbers, emails, URLs, addresses, passwords, financial
details and commitment language before it is spoken.

## Requirements

- A phone that JemRec supports, with setup completed. The target device is a
  Galaxy S24 FE; the base recorder has also been used on an Honor and a
  Galaxy S23.
- Android 12 or newer.
- Termux from F-Droid, with whisper.cpp, Piper and llama.cpp installed.
- A 3B dense model in Q4, and no larger. Llama 3.2 3B Instruct or Qwen2.5
  3B Instruct fit an 8 GB phone.
- JDK 21 and an Android SDK only if you build the APK yourself. No root.

The assistant is slow on a phone: Whisper and a 3B model take a few seconds
per turn, so the caller hears a pause before each reply.

## Quick start

### 1. Build or install the app

To build from a clean checkout:

```bash
export JAVA_HOME=/path/to/jdk-21
export JEMREC_JAVA_HOME="$JAVA_HOME"
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Uninstall the official JemRec first. The fork's debug APK cannot install over
it, and setup has to pair again.

### 2. Set up the phone

Complete JemRec's own setup, including the recorder checks and Exact call
tracking. Turn the answering machine on. No greeting is needed: the AI
service speaks its own first line.

### 3. Install the Termux service

Follow [docs/AI-SERVICE.md](docs/AI-SERVICE.md). It builds whisper.cpp,
installs Piper and llama.cpp, copies `tools/ai/receptionist.py` to the phone,
and starts the service.

Set the name the assistant answers for on the phone, not in Git:

```bash
export JEMREC_OWNER="Your Name"
```

Without it the assistant says "the owner", which works but sounds impersonal.

### 4. Test it

The app's **Test call** checks the phone's audio route with a tone and beep.
It does not start the assistant. To test the assistant, call the phone from
another phone and let it ring past the answer delay. The app should say
"Assistant answering", and the caller should hear the automated intro.

The app's default longest call is 60 seconds. Set it to 120 seconds before a
real conversation.

## Source and GitHub

The public repository is
[AydenForCybersec/jemrec-receptionist](https://github.com/AydenForCybersec/jemrec-receptionist).
It is Apache-2.0, with third-party notices, no signing keys, no personal notes
and no real call data.

To build from a clone, see [docs/GITHUB.md](docs/GITHUB.md). The same guide
covers publishing your own fork and the repository settings worth turning on.

## Documentation

- [docs/AI-RECEPTIONIST.md](docs/AI-RECEPTIONIST.md): how the fork works, the
  socket protocol, the app's live screen, and the safety rails.
- [docs/AI-SERVICE.md](docs/AI-SERVICE.md): install Termux, whisper.cpp,
  Piper and llama.cpp, then run the service.
- [docs/GITHUB.md](docs/GITHUB.md): publish this tree to your GitHub, build
  it from a clean clone, and configure the repository.
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and
  [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md): the upstream recorder's design
  and build details.
- [README-UPSTREAM.md](README-UPSTREAM.md): the original JemRec README,
  including the recorder manual and F-Droid links.

## Privacy and limits

- Everything runs on the phone. There is no account, cloud service or
  analytics.
- Assistant calls are not recorded. The transcript is held in memory for the
  live screen and written to `~/receptionist.log` by the Termux service.
- Notes for assistant mode are read from `~/assistant.txt`. Keep secrets and
  personal data out of that file because the model may repeat it.
- The model can still be wrong. The rails stop the dangerous categories, not
  every odd sentence.
- The receptionist does not answer messenger or meeting calls.

## License

Apache License 2.0. The upstream JemRec code and documentation remain under
their original copyright and license. See [LICENSE](LICENSE),
[NOTICE.md](NOTICE.md) and [README-UPSTREAM.md](README-UPSTREAM.md).
