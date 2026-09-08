# AnoTerm

[日本語](README.md) | **English**

An SSH client and terminal emulator for Android. Kotlin + Jetpack Compose.

Its reason for existing is **rendering full-width characters at the right
width**. Terminal apps on Android routinely misplace the cursor once CJK text
appears, and the display falls apart from there. Character width resolution and
UTF-8 decoding are implemented here rather than delegated, which is why.

## Features

- **Terminal emulation** — implemented in `TerminalEmulator.kt`
- **Correct rendering of wide and combining characters** — `CharWidth.kt`
  resolves East Asian Width; `Utf8Decoder.kt` handles surrogate pairs and
  partial byte sequences
- **Host management** — host, port and username stored in Room
- **Authentication** — password or private key, including passphrase-protected keys
- **Key installation (`ssh-copy-id` equivalent)** — over a working password
  session, the key icon appends your public key to the host's
  `~/.ssh/authorized_keys`. Moving to key auth needs nothing but the phone.
  Idempotent, and it does not disturb keys already there
- **Automatic tmux attach** — set a session name on the host and the app sends
  `tmux new -A -s <name>` after connecting (`-A` creates it if absent), so work
  survives a dropped connection
- **tmux dashboard** — lists the windows on the server and switches between them
  with a tap. The list comes from `list-windows` run on a separate SSH session
  rather than typed into the terminal, so nothing lands in the middle of your
  work. The prefix-shortcut buttons are still there
- **Host key verification (TOFU)** — the first key is recorded and a **changed
  key refuses the connection**. The fingerprint is shown on that first connect
  so you can check it against `ssh-keygen -lf` on the server
- **Encrypted credential storage** — `EncryptedFile` under an Android Keystore key
- **App lock** — optional biometric
- **Command history**
- **Background connections** — a foreground service keeps the session alive
- **Display options** — font size, colours

## Language

**The UI is largely Japanese.** `strings.xml` defaults to English and a Japanese
translation exists, but roughly 300 strings across 20 screens are written
directly in Kotlin in Japanese and bypass the resource system — settings, key
management, help sheets and the foreground-service notification among them.

Terminal output itself is unaffected: that comes from the remote host. What is
Japanese is the app's own chrome. If you read Japanese this is a non-issue; if
you do not, expect to navigate by icon and position. Extracting those strings
into resources is welcome as a contribution.

## Requirements

- Android 7.0 (API 24) or later
- JetBrains Mono is bundled, so rendering does not depend on device fonts

## Install

### From the APK

Download `app-release.apk` from
[Releases](https://github.com/mirute02/wanoterm/releases) in the browser on your
Android device and tap it — from the notification or a file manager. No PC and
no adb needed.

Android will ask you to allow installs from that source the first time. Since
8.0 this is per-app, so allowing your browser or file manager once is enough.

Because it does not come from the Play Store, Play Protect may show a
confirmation screen. The build is self-signed; the certificate fingerprint is
published with each release.

### Building it yourself

```bash
git clone https://github.com/mirute02/wanoterm.git
cd wanoterm
./gradlew assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`. Without signing
properties the release variant builds unsigned and is not suitable for
distribution.

## Build

```bash
./gradlew assembleDebug
```

`namespace` / `applicationId` is `app.anoterm` (`.debug` suffix for debug builds).

### Tests

```bash
./gradlew test
```

43 unit tests cover terminal emulation, character width, UTF-8 decoding and IME
composing state. CI runs them on every push.

### Release builds

Signing keys are not in the repository. Put them in `gradle.properties` or CI
secrets:

```properties
WANOTERM_STORE_FILE=/absolute/path/to/release.jks
WANOTERM_STORE_PASSWORD=...
WANOTERM_KEY_ALIAS=...
WANOTERM_KEY_PASSWORD=...
```

The certificate DN is readable by anyone holding the APK, so put a project name
there rather than your own. A key cannot be changed once you have shipped with
it — see [docs/RELEASE_SIGNING.md](docs/RELEASE_SIGNING.md).

`signingConfigs` in `app/build.gradle.kts` reads these through
`project.findProperty()`, so a checkout without them still builds — unsigned.

See [docs/RELEASE_SIGNING.md](docs/RELEASE_SIGNING.md).

## Security

An SSH client holds credentials for the hosts you reach and becomes a terminal
that can run anything there. [docs/security.md](docs/security.md) sets out that
credentials are encrypted at rest, that a changed host key **refuses the
connection rather than warning and continuing**, and the limit that matters:
if the device lock is broken, the credentials can be recovered.

Privacy policy: [docs/PRIVACY_POLICY.md](docs/PRIVACY_POLICY.md).

## Licence

MIT — see [LICENSE](LICENSE). Dependency licences, read from each module's POM
rather than from project READMEs, are in
[THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).

SSH is a registered trademark of SSH Communications Security. This project is
an independent client and is not affiliated with them.
