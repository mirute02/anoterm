# Third-party licences

This project is MIT ([LICENSE](LICENSE)). No third-party source is vendored
here. Licences below were read from each module's POM on Maven Central, not
from project READMEs.

## Bundled in the APK

| Component | Version | Licence | Role |
|---|---|---|---|
| [sshj](https://github.com/hierynomus/sshj) | 0.40.0 | Apache-2.0 | SSH transport, key exchange, authentication |
| [Bouncy Castle](https://www.bouncycastle.org/) `bcprov-jdk18on` | 1.78.1 | Bouncy Castle Licence (MIT-style) | Cryptographic primitives for sshj |
| [Bouncy Castle](https://www.bouncycastle.org/) `bcpkix-jdk18on` | 1.78.1 | Bouncy Castle Licence (MIT-style) | Private key parsing (PKCS#8, OpenSSH) |
| [slf4j-android](https://www.slf4j.org/android/) | 1.7.36 | MIT | Logging binding required by sshj |
| AndroidX (core, lifecycle, activity, compose, navigation3, room, biometric, security-crypto, appcompat) | see `gradle/libs.versions.toml` | Apache-2.0 | Application framework |
| Kotlin standard library, kotlinx-coroutines, kotlinx-serialization | 1.10.2 / 1.7.3 | Apache-2.0 | Language runtime |
| Google Tink (transitive via `security-crypto`) | — | Apache-2.0 | Encrypted file storage |

None are copyleft. All permit distribution of a closed or open binary.

## Fonts

[JetBrains Mono](https://github.com/JetBrains/JetBrainsMono) (regular, bold) is
bundled under the SIL Open Font License 1.1. The OFL permits bundling in an
application; the font is not renamed and is not sold on its own.

## Not used

Earlier revisions of this project used JSch. It was replaced by sshj, and the
only remaining references are two stale comments. If you are auditing this
repository, `net.schmizz.*` is the SSH implementation in use.

## Trademark

SSH is a registered trademark of SSH Communications Security. This project is
an independent client and is not affiliated with them.
