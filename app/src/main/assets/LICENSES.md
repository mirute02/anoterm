# Third-party licenses used in AnoTerm

This document enumerates third-party components bundled in the released APK.
All are compatible with Play Store distribution (closed-source permitted).

## sshj

- Source: https://github.com/hierynomus/sshj
- Version: 0.40.0
- License: Apache License 2.0

## Bouncy Castle (bcprov-jdk18on, bcpkix-jdk18on)

- Source: https://www.bouncycastle.org/
- Version: bcprov 1.80.2 / bcpkix 1.80 / bcutil 1.80.2 (resolved; sshj requires [1.80,1.81))
- License: Bouncy Castle Licence (MIT-style)

## Also present at runtime

- asn-one 0.6.0 — Apache-2.0 (required by sshj)
- slf4j-api 2.0.17 — MIT
- Google Tink (tink-android) 1.8.0 — Apache-2.0
- Gson 2.8.9 — Apache-2.0
- JSpecify 1.0.0 — Apache-2.0

## JetBrains Mono (bundled font)

- Source: https://github.com/JetBrains/JetBrainsMono
- Copyright 2020 The JetBrains Mono Project Authors
  (https://github.com/JetBrains/JetBrainsMono)
- License: SIL Open Font License, Version 1.1
- Full license text: [OFL-1.1.txt](OFL-1.1.txt) (bundled in this APK)

The font is bundled unmodified and is not renamed. The OFL permits bundling with
an application; it requires the copyright notice and the license to travel with
the font, which is why the full text ships here rather than as a link.

## slf4j-android

- Source: https://www.slf4j.org/android/
- Version: 2.0.17-0
- License: MIT

## AndroidX libraries (core, lifecycle, compose, activity, navigation3, room, biometric, security-crypto, appcompat)

- Source: https://developer.android.com/jetpack
- License: Apache License 2.0

## Kotlin standard library, kotlinx-coroutines, kotlinx-serialization

- License: Apache License 2.0

## Google Tink (transitive via security-crypto)

- License: Apache License 2.0

## Material Icons (extended)

- License: Apache License 2.0

---

If you ship this APK, include a copy of the Apache License 2.0 text
(see https://www.apache.org/licenses/LICENSE-2.0.txt) alongside this file.
