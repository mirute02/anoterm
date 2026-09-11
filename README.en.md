# AnoTerm

[日本語](README.md) | **English**

An SSH client and terminal emulator for Android. Kotlin + Jetpack Compose.

Its reason for existing is **rendering full-width characters at the right
width**. Terminal apps on Android routinely misplace the cursor once CJK text
appears, and the display falls apart from there. Character width resolution and
UTF-8 decoding are implemented here rather than delegated, which is why.

## Features

### As a terminal

- **Wide and combining characters at the right width** — `CharWidth.kt` resolves East
  Asian Width and `Utf8Decoder.kt` handles surrogate pairs and partial byte sequences.
  CJK in an Android terminal usually drifts the cursor and breaks the display; that is
  why both are implemented here rather than borrowed
- **Terminal emulation** — `TerminalEmulator.kt`: alternate screen, scroll regions,
  mouse reporting, DECCKM and the rest
- **Find in the history** — from the ⋮ menu. Searches what came through this connection,
  not the shell's history and not the files. Matches are painted; the arrows step
  between them
- **Scrolling with momentum** — flick and it keeps going. While you are back in the
  history a button says how far back, and returns you to the latest
- **The line you are reading stays put when the screen changes height** — rows pushed off
  the top go into the scrollback instead of being dropped, and come back out when it
  grows again. Reading history, the top line does not move at all

### Connecting and authenticating

- **Host management** — host, port and username stored in Room
- **Authentication** — password or private key, including passphrase-protected keys
- **Key installation (`ssh-copy-id` equivalent)** — over a working password connection,
  appends your public key to `~/.ssh/authorized_keys` on the far end, so you can move to
  key auth from the phone alone. It does not duplicate an existing entry or disturb the
  others
- **Host key verification (TOFU)** — the first key is recorded and a **changed key
  refuses the connection**. The fingerprint is shown on that first connect so you can
  check it against `ssh-keygen -lf` on the server
- **Encrypted credential storage** — `EncryptedFile` under an Android Keystore key
- **App lock** — optional biometric
- **Background connections** — a foreground service keeps the session alive with the
  screen off

### tmux

- **Attaches itself** — put a session name in the host and `tmux new -A -s <name>` is
  sent on connect, so a dropped connection does not cost you the work
- **Every window, in one drawer (≡)** — pulled out from the left: every window on every
  open connection, as connection → session → window. **One tap reaches any of them.**
  Windows that produced output are marked, and what is running in them is shown
- **Switching bar** — the tmux windows of the connection you are on, one tap each.
  Can be hidden from the ⋮ menu
- **No prefix key is ever sent** — every tmux action runs a `tmux` command instead, so it
  works whatever has hold of Ctrl-B and whatever prefix `.tmux.conf` binds
- The listing is read over a separate SSH session rather than typed into the terminal, so
  it never mixes into what you are working on

### Checking things from the phone

- **Open an image** — a path on screen is underlined and tapping it shows the picture.
  Claude Code and Codex write a chart or a screenshot and print where they put it, which
  from a phone is the end of the road. Pinch to zoom
- **Open a document** — `.md`, `.kt`, `.json`, `.diff` and some fifty more, with line
  numbers and no wrapping (wrapped code loses its indentation and with it its shape)
- **See the page beside the terminal** — tapping an `http://` URL opens a browser pane
  next to the terminal. **The traffic goes through this SSH connection**, so a dev server
  bound to `localhost` is reachable without exposing it; the forward listens on 127.0.0.1
  on the phone only. The split runs stacked or side by side and the bar between them sets
  the share. It can also be handed to the phone's browser for a full screen
- All of this reads over **a separate SSH session**, so nothing lands in the command line
  you are working in. Relative paths are looked for where the tmux pane is

### Built for a small screen

- **Terminal only** — the bar, the tmux row and the system bars all go. Back leaves it
- **Tabs live in the bar** — a tab name says which connection you are on and so did the
  bar's title, so they are one row now
- **Floating reply pad** — while the keyboard is away, 1/2/3, Esc, a keyboard key and
  Shift+Tab sit over the terminal, so answering an approval prompt does not mean opening
  the IME and hiding what you are approving. Drag it wherever it suits your hand
- **Auxiliary keys only while typing** — Esc, the arrows, Ctrl and Shift+Tab come and go
  with the keyboard. The screen is too small to hold them while reading
- **Avoids the inner camera** — unfolded, the camera sits inside the screen; the gap
  follows whatever the system reports as obstructed. A left margin is settable if that
  is not enough
- **The cursor goes where you touched** — wherever the far end asked for mouse reporting
  (Claude Code, vim, tmux with mouse on)

### The rest

- **Gestures** — bottom quarter opens the keyboard, swipe scrolls, double tap sends Tab,
  long press and drag selects and copies, pinch changes the text size. All of it is in
  the Help sheet
- **Command history**, **notes**, **custom shortcuts**
- **Display options** — text size (pinch changes it and it stays changed), line spacing,
  colours

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
confirmation screen. The build is self-signed, and the certificate is recorded here
rather than promised per release — a promise like that becomes a lie on the first
release someone forgets:

```
CN=AnoTerm, OU=AnoTerm, O=AnoTerm, C=JP
SHA-256: 7e1677c2e1094ca36b9584990beabb2fb1668b327417ead3b0993ec1b99bbbdb
```

Check an APK against it with `apksigner verify --print-certs <apk>`.
**This fingerprint cannot change.** Android treats an app signed by a different key as
a different app and refuses to update over it, so an APK with another fingerprint is
not from this project.

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

124 unit tests, run on every push. They cover terminal emulation, character width,
UTF-8 decoding, IME composing state, rows surviving a resize, picking paths and URLs out
of the screen (rejoining across a wrap, shell quoting), parsing tmux output, and building
forward targets.

Development happens without a device attached, so **anything that has to be looked at is
not covered**. Whatever can be pulled out as logic is pulled out and tested; how drawing
and input actually feel is checked by installing a release.

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
