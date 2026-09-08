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
- **Host key verification (TOFU)** — the first key is recorded, and a
  **changed key refuses the connection**
- **Encrypted credential storage** — `EncryptedFile` under an Android Keystore key
- **App lock** — optional biometric
- **Command history**
- **Background connections** — a foreground service keeps the session alive
- **Display options** — font size, colours

## Requirements

- Android 8.0 (API 26) or later
- JetBrains Mono is bundled, so rendering does not depend on device fonts

## Build

```bash
./gradlew assembleDebug
```

`namespace` / `applicationId` is `app.anoterm` (`.debug` suffix for debug builds).

### Tests

```bash
./gradlew test
```

Unit tests cover terminal emulation, character width, UTF-8 decoding and IME
composing state. CI runs them on every push.

### Release builds

Signing keys are not in the repository. Put them in `gradle.properties` or CI
secrets:

```properties
WANOTERM_STORE_FILE=~/.android/wanoterm-upload.jks
WANOTERM_STORE_PASSWORD=...
WANOTERM_KEY_ALIAS=...
WANOTERM_KEY_PASSWORD=...
```

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

## Related

- [tvremocon](https://github.com/mirute02/tvremocon) — an Android widget that
  drives a TV through a Tapo infrared hub
