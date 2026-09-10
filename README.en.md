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
- **Window list (≡)** — every open SSH connection and the tmux behind it, as a
  tree: connection, then session, then window. **One tap reaches any window
  anywhere**, including one in another connection — the app moves to that tab,
  switches which session that client is attached to, and selects the window.
  The connections are read in parallel, only when the list is opened
- **tmux switcher bar** — the server's tmux windows sit in a strip under the
  connection tabs, so **one tap reaches a window in the background** instead of
  repeating `prefix n`. When more than one session exists, the menu on the left
  changes which one you are attached to. The list is read over a separate SSH
  session rather than typed into the terminal, so nothing lands in the middle of
  your work, and it refreshes every ten seconds only while the app is in front
- **tmux dashboard** — splits, pane movement, copy mode and renaming, as buttons.
  **No prefix key is ever sent**: each one runs a `tmux` command on a separate
  session, so it works whatever has hold of Ctrl-B and whatever prefix you have
  bound
- **Open an image** — a path on screen is underlined, and tapping it shows the
  picture. Claude Code and Codex write a chart or a screenshot and print where
  they put it, which on a phone is the end of the road. The file is read over a
  second SSH session, so no `base64` lands in the shell you are working in.
  A relative path is looked for where the tmux pane is. Pinch to zoom
- **See the page beside the terminal** — an `http://` URL on screen is
  underlined, and tapping it opens a browser pane next to the terminal.
  **The traffic goes through this SSH connection**, so a dev server bound to
  `localhost` is reachable without exposing it. The forward listens on 127.0.0.1
  on the phone only; nobody else on the Wi-Fi can reach it. The split runs
  stacked or side by side, and the bar between them sets the share. It can also
  be handed to the phone's browser for a full screen
- **Floating reply pad** — while the keyboard is away, 1/2/3 sit over the
  terminal in a triangle, so answering an approval prompt does not mean opening
  the IME and hiding what you are approving. Drag it wherever it suits your hand
- **Auxiliary keys only while typing** — Esc, the arrows and Ctrl come and go
  with the keyboard. The screen is too small to hold them while reading
- **Host key verification (TOFU)** — the first key is recorded and a **changed
  key refuses the connection**. The fingerprint is shown on that first connect
  so you can check it against `ssh-keygen -lf` on the server
- **Encrypted credential storage** — `EncryptedFile` under an Android Keystore key
- **App lock** — optional biometric
- **Command history**
- **Background connections** — a foreground service keeps the session alive
- **Display options** — font size, colours

## Language

English and Japanese. It follows the device setting by default and can be
changed in Settings without restarting the app.

Every string lives in `strings.xml`; none are left in Kotlin.
`tools/check-strings.py` runs in CI and rejects a duplicated name, a name
present in one language and not the other, format arguments that differ between
the two, and names nothing refers to.

Terminal output is unaffected either way: that comes from the remote host.

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

## Changes

[CHANGELOG.md](CHANGELOG.md) lists what changed in each version (in Japanese).
APKs are on the [releases page](https://github.com/mirute02/wanoterm/releases).

## Licence

MIT — see [LICENSE](LICENSE). Dependency licences, read from each module's POM
rather than from project READMEs, are in
[THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).

SSH is a registered trademark of SSH Communications Security. This project is
an independent client and is not affiliated with them.
