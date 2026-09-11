# Third-party licences

This project is MIT ([LICENSE](LICENSE)). No third-party source is vendored here.

Versions are the ones `releaseRuntimeClasspath` actually resolves, which is not always
what `gradle/libs.versions.toml` declares — sshj constrains Bouncy Castle to
`[1.80,1.81)`, so the 1.78.1 in the catalog is overridden. How each licence was
established is recorded at the end of this file.

## Bundled in the APK

| Component | Version | Licence | Role |
|---|---|---|---|
| [sshj](https://github.com/hierynomus/sshj) | 0.40.0 | Apache-2.0 | SSH transport, key exchange, authentication |
| [Bouncy Castle](https://www.bouncycastle.org/) `bcprov-jdk18on` | 1.80.2 | Bouncy Castle Licence (MIT-style) | Cryptographic primitives for sshj |
| [Bouncy Castle](https://www.bouncycastle.org/) `bcpkix-jdk18on` | 1.80 | Bouncy Castle Licence (MIT-style) | Private key parsing (PKCS#8, OpenSSH) |
| [Bouncy Castle](https://www.bouncycastle.org/) `bcutil-jdk18on` | 1.80.2 | Bouncy Castle Licence (MIT-style) | Pulled in by bcpkix |
| [asn-one](https://github.com/hierynomus/asn-one) | 0.6.0 | Apache-2.0 | ASN.1 parsing, required by sshj |
| [slf4j-api](https://www.slf4j.org/) | 2.0.17 | MIT | Logging facade, required by sshj |
| [Gson](https://github.com/google/gson) | 2.8.9 | Apache-2.0 | Transitive via Tink |
| [JSpecify](https://jspecify.dev/) | 1.0.0 | Apache-2.0 | Annotations, transitive via Gson |
| [slf4j-android](https://www.slf4j.org/android/) | 2.0.17-0 | MIT | Logging binding required by sshj |
| AndroidX (core, lifecycle, activity, compose, navigation3, room, biometric, security-crypto, appcompat) | see `gradle/libs.versions.toml` | Apache-2.0 | Application framework |
| Kotlin standard library, kotlinx-coroutines, kotlinx-serialization | 1.10.2 / 1.7.3 | Apache-2.0 | Language runtime |
| [Google Tink](https://github.com/tink-crypto/tink-java) `tink-android` | 1.8.0 | Apache-2.0 | Encrypted file storage, transitive via `security-crypto` |

None are copyleft. All permit distribution of a closed or open binary.

## Fonts

[JetBrains Mono](https://github.com/JetBrains/JetBrainsMono) Regular is bundled under
the SIL Open Font License 1.1. (Bold is not: the renderer synthesises weight with
`isFakeBoldText`, so resource shrinking drops the bold face from the release APK.)

Copyright 2020 The JetBrains Mono Project Authors
(https://github.com/JetBrains/JetBrainsMono)

The OFL permits bundling in an application; the font is not renamed and is not
sold on its own. The OFL also requires the copyright notice and the license to
travel with the font, so the full text ships inside the APK at
`app/src/main/assets/OFL-1.1.txt` rather than only being linked from here.

## How these were established

Read from the artefact wherever the artefact carries it, since a project README is a
claim and a jar is evidence:

- **sshj** — `Bundle-License: .../LICENSE-2.0.txt` in the jar manifest
- **Bouncy Castle** (`bcprov`) — the `org/bouncycastle/LICENSE` class it ships
- **slf4j-android**, **slf4j-api** — `META-INF/LICENSE` and the POM inside the jar
- **Gson**, **JSpecify** — `Bundle-License` in the jar manifest
- **JetBrains Mono** — the font's own `name` table (IDs 0, 13, 14), which also
  confirms the copyright line in `OFL-1.1.txt` matches the shipped face
- **asn-one**, **bcpkix**, **bcutil**, **tink-android** — these jars carry no licence
  file, so their POMs on Maven Central were used instead. This is the weaker evidence
  in the list and is called out rather than blended in

Nothing here is GPL, AGPL or LGPL, which is what an MIT release requires.

## Not used

Earlier revisions of this project used JSch. It was replaced by sshj, and the
only remaining references are two stale comments. If you are auditing this
repository, `net.schmizz.*` is the SSH implementation in use.

## Trademark

SSH is a registered trademark of SSH Communications Security. This project is
an independent client and is not affiliated with them.
