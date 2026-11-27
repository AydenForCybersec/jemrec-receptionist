# Put this on your GitHub

The public repository is already at
[`AydenForCybersec/jemrec-receptionist`](https://github.com/AydenForCybersec/jemrec-receptionist).
This guide is for building it from a clone or publishing your own fork. In a
clone, `origin` is your repository and `upstream` is the fork source.

If you are only building the existing public repository, skip to
[Build from a clean clone](#3-build-from-a-clean-clone). To publish your own
fork, replace `<your-github-user>` and `<repo-name>` below. A sensible
repository name is `jemrec-receptionist`.

## 1. Create the repository

The easiest route uses the GitHub CLI:

```bash
gh auth login
# If you cloned the public repo, keep its remote as upstream:
git remote rename origin upstream
git remote -v                 # origin must be absent; upstream is the fork source
git status                    # must be clean
gh repo create <repo-name> --public --source=. --remote=origin --push
```

`gh repo create` creates the repository, adds `origin`, and pushes `main`.
Choose `--private` instead of `--public` if you want to review it before
making it visible; you can change visibility later in Settings.

The first commit may be authored as `JemRec Receptionist`. To put it under
your name, set your Git identity and amend it before creating the GitHub
repository:

```bash
git config user.name "Your Name"
git config user.email "you@example.com"
git commit --amend --reset-author --no-edit
```

Without `gh`, create an empty repository on github.com. Do not add a README,
license or `.gitignore` there, because this tree already has them. Then:

```bash
git remote add origin https://github.com/<your-github-user>/<repo-name>.git
git push -u origin main
```

If Git asks for a password, use a personal access token, not your GitHub
password. `gh auth login` handles this for you.

## 2. Configure the repository

On the repository's GitHub page:

- Set the description to "On-phone AI call receptionist for unrooted Android.
  Fork of JemRec."
- Add topics such as `android`, `call-recorder`, `answering-machine`,
  `whisper`, `llama-cpp`, `termux` and `privacy`.
- Under **Settings → Code security**, turn on **Private vulnerability
  reporting** so `SECURITY.md` has a working report path.
- Enable **Discussions** only if you want a question area. The issue form no
  longer sends people to the upstream discussions.
- Under **Actions → General**, leave the default read permissions. The build
  and CodeQL workflows are safe for a public repository.
- Releases are off until you turn them on. The release workflow is gated by
  the repository variable `ENABLE_RELEASES`; leave it unset while you are
  testing. [Signed releases](#signed-releases) has the keystore and secret
  steps. Do not reuse JemRec's signing key.

The upstream `fastlane/` metadata and F-Droid links are kept for reference.
They are not a submission to F-Droid. Do not submit this fork under the
official JemRec package name.

## Signed releases

Every release must be signed by one key, kept for the life of the app. Android
refuses an APK signed by a different certificate, so a new key means every
user uninstalls, and the app loses its pairing and its settings. JemRec's key
is not in this repository. Make your own; do not reuse the upstream one.

### 1. Make the key once

On a computer with JDK 21, outside the repository:

```bash
keytool -genkeypair \
  -keystore jemrec-release.jks \
  -alias jemrec \
  -storetype PKCS12 \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=JemRec Receptionist, O=AydenForCybersec"
```

It asks for a store password and a key password. Use the same long random
password for both: PKCS12 does not keep them apart. Keep the password in a
password manager, and back up the `.jks` somewhere you will still have in ten
years. If it is lost, the next release cannot install over the last one.

Print the certificate digest now. Later releases are checked against it, and
it is public:

```bash
keytool -list -v -keystore jemrec-release.jks -alias jemrec \
  | sed -n 's/^[[:space:]]*SHA256: //p'
```

### 2. Put it in GitHub

The keystore goes into a secret as base64. The file itself never enters Git:
`.gitignore` refuses `*.jks` and `*.keystore`.

Linux:

```bash
base64 -w0 jemrec-release.jks > jemrec-release.jks.b64
```

macOS:

```bash
base64 -i jemrec-release.jks | tr -d '\n' > jemrec-release.jks.b64
```

On the repository page, **Settings → Secrets and variables → Actions**, with
**Secrets** selected for the first four rows and **Variables** for the last
two:

| Kind | Name | Value |
| --- | --- | --- |
| Secret | `JEMREC_KEYSTORE_BASE64` | the one line in `jemrec-release.jks.b64` |
| Secret | `JEMREC_KEYSTORE_PASSWORD` | the store password |
| Secret | `JEMREC_KEY_ALIAS` | `jemrec` |
| Secret | `JEMREC_KEY_PASSWORD` | the key password, the same one |
| Variable | `JEMREC_CERT_SHA256` | the digest from step 1, without colons |
| Variable | `ENABLE_RELEASES` | `true` |

Then delete the encoded copy:

```bash
rm jemrec-release.jks.b64
```

`JEMREC_CERT_SHA256` may be left unset for the very first run: the workflow
then reads the digest out of the keystore, publishes, and warns with the value
to add. Set it before the second release. From then on an APK signed by
another key stops the workflow instead of being published.

### 3. Build it on your machine first

The build CI runs, with the key in the environment, writes
`app/build/outputs/apk/release/app-release.apk`. With none of the variables it
writes `app-release-unsigned.apk` instead, which is what the reproducible
build comparison uses.

```bash
export JEMREC_KEYSTORE="$PWD/../jemrec-release.jks"   # absolute path
export JEMREC_KEYSTORE_PASSWORD='...'
export JEMREC_KEY_ALIAS=jemrec
export JEMREC_KEY_PASSWORD='...'

./gradlew :app:assembleRelease

"$ANDROID_HOME"/build-tools/36.0.0/apksigner verify --print-certs \
  app/build/outputs/apk/release/app-release.apk
```

The digest it prints must equal `JEMREC_CERT_SHA256`, and `aapt2 dump badging`
on the APK must not print `application-debuggable`.

### 4. Run the release

A merge to `main` that changes `jemrecVersionName` and `jemrecVersionCode` in
`gradle.properties` is released from that merge, provided
`fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` exists. The
workflow makes the tag itself.

A tag pushed by hand also works, if it is on `main` and matches
`gradle.properties`:

```bash
VERSION=$(sed -n 's/^jemrecVersionName=//p' gradle.properties)
git tag "$VERSION"
git push origin "$VERSION"
```

Either way the workflow first waits for `build.yml` on the same commit to
pass, then builds, checks that the APK is signed by the pinned key and is not
debuggable, and attaches `jemrec-<version>.apk` to the release.

The debug preview APK on the `preview-arm64-1.9` tag is signed by a debug key.
A release-signed APK cannot install over it, so uninstall that preview first.

## 3. Build from a clean clone

On a computer with JDK 21 exactly and an Android SDK:

```bash
git clone https://github.com/<your-github-user>/<repo-name>.git
cd <repo-name>

export JAVA_HOME=/path/to/jdk-21
export JEMREC_JAVA_HOME="$JAVA_HOME"
export ANDROID_HOME=/path/to/android-sdk

./gradlew :app:assembleDebug
```

The APK is at `app/build/outputs/apk/debug/app-debug.apk`.

The daemon compiles against Android 36 and is dexed with build-tools 36.0.0.
Install those SDK packages if Gradle stops in `shellserver/build.sh`:

```bash
sdkmanager "platforms;android-36" "build-tools;36.0.0"
```

Useful checks:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :daemon-tests:test :daemon-tests:errorProne
python3 tools/ai/receptionist.py --selftest
```

The full app suite takes about eleven minutes on a small machine.

## 4. Install it on the phone

1. Uninstall the official JemRec if it is installed. The debug APK uses a
   different signing key and cannot upgrade over it.
2. Install the APK:

   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

3. Complete JemRec's setup and let its recorder checks pass. Turn on the
   answering machine. Exact call tracking is required.
4. Set the longest call to 120 seconds for a real conversation.

## 5. Install the Termux side

Follow [AI-SERVICE.md](AI-SERVICE.md) for whisper.cpp, Piper and llama.cpp.
The part that matters for a GitHub checkout is copying the service:

```bash
adb push tools/ai/receptionist.py /sdcard/Download/
```

Then in Termux:

```bash
mkdir -p ~/receptionist
cp /sdcard/Download/receptionist.py ~/receptionist/
cd ~/receptionist

export JEMREC_OWNER="Your Name"
python receptionist.py --selftest
python receptionist.py
```

Keep `JEMREC_OWNER` on the phone, in `~/.bashrc` or the command line. It is
the name the assistant says it answers for. Do not put your real name,
`assistant.txt`, call logs or recordings in the repository.

## 6. Updating the phone

After changing the code:

```bash
git pull
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Copy `tools/ai/receptionist.py` to the phone again if it changed, and restart
the Termux service.

## Fork branding before a public build

A few upstream links are deliberately left in place so the history stays
auditable. Change these before you distribute a build under your own name:

- `app/src/main/java/com/jemcik/jemrec/ui/SettingsActions.kt`,
  `NEWEST_RELEASE`: points at this repository's releases at
  `https://github.com/AydenForCybersec/jemrec-receptionist/releases`.
  Change it and the matching test if you rename the repository.
- `app/src/main/res/values/strings.xml`, `app_name`: still says JemRec. Rename
  it if your build should not look like the official app.
- `app/build.gradle.kts`, `applicationId`: still `com.jemcik.jemrec`. Change it
  if your build must install beside the official app. That also changes the
  package name Android and Termux see, so test setup again.
- `docs/*.html` is the generated upstream JemRec website, with its F-Droid and
  donation links. Do not enable GitHub Pages for the fork without changing
  the generator and regenerating the site.
- `fastlane/` is upstream store metadata. Do not submit this fork to F-Droid
  under the official package name.

The README, `NOTICE.md` and this guide already say it is a fork. Keep those
statements if you rename the app.

## What not to commit

The repository ignores signing keys, APKs, audio files, Python bytecode and
the upstream build output. It also ignores `.env`, `assistant.txt` and
receptionist logs. Check before your first push:

```bash
git status --short
git grep -nEi 'api[_-]?key|secret|password|BEGIN (RSA|OPENSSH|PRIVATE)'
```

The protocol tokens in the source are generated at runtime, not credentials.
Do not commit a filled `.env` or a keystore.

## Attribution

Keep `LICENSE`, `NOTICE.md` and `README-UPSTREAM.md`. The app is a fork of
JemRec, which is Apache-2.0, and the vendored scrcpy code has its own notice.
If you change the app's package name or publish a build, make clear that it is
a fork and not an official JemRec release.
