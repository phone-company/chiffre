# Chiffre: security design

[English](SECURITY-DESIGN.md) | [Deutsch](SECURITY-DESIGN.de.md)

This document describes how Chiffre protects your notes, what it deliberately does not protect, and how you can check the claims yourself. Chiffre is a one-person project and has **not been independently audited**. Everything below is written so that you can verify it against the source code (`app/src/main/assets/index.html` and `app/src/main/java/app/chiffre/`).

For reporting vulnerabilities see [SECURITY.md](../SECURITY.md).

## 1. What Chiffre is meant to protect

Chiffre protects **stored notes at rest** when someone gets access to the device or to a copy of its app data, but does not know your password. It makes no stronger promise than that.

Attacker models:

| Attacker | Protected? |
|---|---|
| Someone who finds or steals the locked phone | Yes, notes are encrypted and the key only exists while the app is unlocked |
| Someone who copies the app data or a backup file and tries passwords offline | Only as strongly as your password (see 3) |
| Apps on the same phone | Yes, Android keeps the app storage private; the app has no internet permission and exports nothing by itself |
| Malware on an unlocked device, keyloggers, malicious accessibility services | **No** |
| Someone who can force you to unlock the app or to press your finger on the sensor | **No** |
| Forensic analysis of memory or storage by a capable attacker | **No guarantees** |

## 2. Architecture

- The Android app is a thin shell (`MainActivity` and a few helper classes) around a single web page (`index.html`) shown in an Android WebView. The page is served from the app's own assets under the virtual address `https://chiffre.invalid/`. The shell blocks all other requests and the app has no `INTERNET` permission.
- Encryption is done with the **Web Crypto API** (`crypto.subtle`) of the WebView. There are no third-party libraries. The one piece of own cryptographic code is **Argon2id**, written in C and compiled to a small WebAssembly module embedded in `index.html` (source, build script and checks in `tools/argon2/`, see section 3).
- The same page also runs as a web app (PWA) in a browser. This document is about the Android app.

## 3. Cryptography

| Item | Value |
|---|---|
| Key derivation | **Argon2id** (RFC 9106): 64 MiB memory, 6 passes, 1 lane, 32-byte output, random 16-byte salt per vault. Vaults created with 3 passes (version 1.45.37 and 1.45.38) are raised to 6 passes automatically at the next unlock; a stronger vault is never weakened. Vaults created before version 1.45.37 used PBKDF2-HMAC-SHA-256 with 600,000 iterations and are converted automatically at the next unlock. |
| Cipher | AES-256-GCM, random 12-byte IV for every encryption |
| Derived key | Non-extractable `CryptoKey` (it cannot be exported from JavaScript) |
| Password check | None stored. A wrong password makes the GCM authentication tag fail. |
| Minimum password length | 12 characters (a strength estimate is shown, only the length is enforced) |

**Vault format.** The vault is stored in the WebView's `localStorage` under the key `chiffre.vault` as JSON:

```
{ "v": 2, "kdf": "argon2id", "m": 65536, "t": 3, "p": 1, "salt": "<base64>", "iv": "<base64>", "ct": "<base64>" }
```

The older format (`v` 1) is `{ "v": 1, "iter": 600000, "salt": …, "iv": …, "ct": … }` with PBKDF2. In format 2 the header values (`kdf`, `m`, `t`, `p`, `salt`) are bound to the encryption as AES-GCM additional authenticated data (`chiffre-vault-v2|kdf|m|t|p|salt`): if someone changes them, decryption fails.

`ct` is the AES-GCM output (ciphertext followed by the 16-byte tag) of the JSON array of all notes (text, titles, tags, colors, drawings, timestamps, checklist state). The whole array is sealed as one blob and re-sealed with a fresh IV on every save.

**Images.** Images are scaled down (longest side 1280 px) and stored in IndexedDB (`chiffre-img`). Each image is sealed separately with the same vault key and its own random IV. Drawings are part of the note and therefore inside the vault blob.

**Password change.** A new random salt is generated, the vault and all images are re-encrypted with the new key. Old backup files stay readable with the old password.

**Backup files.** A backup file contains the sealed vault and the sealed images in the same format. It is exactly as secure as the vault itself.

**About the Argon2id code.** Web Crypto has no Argon2, so Chiffre contains its own implementation: `tools/argon2/argon2id.c` (about 250 lines, with Blake2b), compiled with `clang --target=wasm32` to a module of about 10 KB that is embedded in `index.html` as base64. `tools/argon2/build.sh` rebuilds it and prints the SHA-256 (`17b50500f81c69cbea084395e6c79aee311c0e0daf21a6febb8088f701dea0c6` for the module shipped in 1.45.37). `tools/argon2/verify.py` compares it with two independent implementations (`libargon2` and Python's `cryptography`) on 68 fixed and random cases, including several lanes and output lengths, and all results must be identical. On every start Chiffre also runs a short known-answer test and refuses to use the module if it fails. A fresh WebAssembly instance is used for every derivation, so the 64 MiB of memory are released afterwards. The Content-Security-Policy allows WebAssembly (`'wasm-unsafe-eval'`) but still no `eval` of JavaScript. The derivation runs in a separate thread (`argon-worker.js`, a Web Worker from the app's own files) so the screen does not freeze while unlocking. The thread receives only the compiled module, the password bytes and the salt, has no storage access, and is ended afterwards; it contains the same computing core as `index.html`, and a short known-answer test on first use checks that its result is correct. If the thread is unavailable or fails, the page computes the key itself.

This is our own implementation of a standard algorithm. The tests catch calculation errors, not design mistakes, and it has not been audited by a third party.

**Limits of the KDF.** Argon2id with 64 MiB makes guessing on GPUs far more expensive than PBKDF2, but it does not make a weak password strong. The strength of your password still decides. Five or six random words are recommended. Backup files created before 1.45.37 still use PBKDF2 and are as easy to attack as before: replace them with new backups.

### Test vector (format 2, Argon2id)

Password `correct horse battery staple`, salt bytes `00 01 02 … 0f`, IV bytes `a0 a1 … ab`, Argon2id with 64 MiB, 3 passes, 1 lane. Same plaintext as below. The derived key (hex) is:

```
0d1a3c6523c8f06e4e0af9c515aa5b5448cfebd6838f2d52c3d8b6ef8ddc3c2e
```

The resulting vault (additional authenticated data: `chiffre-vault-v2|argon2id|65536|3|1|AAECAwQFBgcICQoLDA0ODw==`) is:

```json
{"v":2,"kdf":"argon2id","m":65536,"t":3,"p":1,"salt":"AAECAwQFBgcICQoLDA0ODw==","iv":"oKGio6Slpqeoqaqr","ct":"0Q36TRknoGQWv0axYZahDhJCwLeCXER9nNx2NEel0Tz4sKhGU/VZFM8LhK0OlYRNciRoFLCI+G3Ab/ZU9NA5QCNyO+44WtxseS2Q7l8g1VFTzhuhQZftYeGFl80lWl4NrUm0GA=="}
```

This vector was generated with Python's `cryptography` package (`Argon2id` and `AESGCM`) and confirmed to decrypt with the app's own `keyFor` and `unseal` functions; changing `m` makes decryption fail.

### Test vector (format 1, PBKDF2)

You can check the format and the key derivation without running the app. Password `correct horse battery staple`, salt bytes `00 01 02 … 0f`, IV bytes `a0 a1 … ab`, 600,000 iterations. The plaintext is:

```
[{"id":"n1","text":"Hallo Chiffre","created":1700000000000,"expires":1700600000000}]
```

The derived key (hex) is:

```
ef177144eec9420cbc1093d2a8b344a92bc506d0d4ec9c028dd19f8324d8c1e6
```

The resulting vault is:

```json
{"v":1,"iter":600000,"salt":"AAECAwQFBgcICQoLDA0ODw==","iv":"oKGio6Slpqeoqaqr","ct":"8BWR6R8X/8pZGzCeJuFoy4x81RexfeXZNPJmgVdQR40347wM0DNBnjqeaIPoRxIY1qekOZ3sxOEVt2WJQ6l0c6T/IJdQLyW3C0fUeGO7Ke3K/qEI0Nt8tZTw3NqzE4oK2PPDYg=="}
```

This vector was generated with Python's `cryptography` package and confirmed to decrypt with the app's own `deriveKey` and `unseal` functions. To recompute it yourself:

```python
import hashlib, json, base64
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

key = hashlib.pbkdf2_hmac("sha256", b"correct horse battery staple", bytes(range(16)), 600000, 32)
pt = b'[{"id":"n1","text":"Hallo Chiffre","created":1700000000000,"expires":1700600000000}]'
ct = AESGCM(key).encrypt(bytes(range(0xa0, 0xac)), pt, None)
print(key.hex()); print(base64.b64encode(ct).decode())
```

## 4. Fingerprint unlock (optional)

- Off by default. When you turn it on, Chiffre encrypts your **password** with an AES-256-GCM key stored in the **Android Keystore** (alias `chiffre_bio_v1`, in secure hardware on most devices). The encrypted password and its IV are stored in the app's private preferences.
- The key requires user authentication for every use with a **strong biometric** (Class 3), and is **invalidated when a new fingerprint is enrolled**. An attacker who knows your device PIN and adds their own finger therefore cannot use it. Chiffre then asks for the password again.
- The password always keeps working. Chiffre also asks for it again at a regular interval (every 4 weeks by default) so that you do not forget it. This is a reminder, not extra protection.
- Without the fingerprint feature nothing is remembered.
- **Not protected:** someone who can force your finger onto the sensor. Turn the feature off, or lock the app first, if that is part of your risk.
- The security of this feature depends on the device. On a device without secure hardware, the Keystore protects less.

## 5. Runtime protections of the Android app

- `FLAG_SECURE`: screenshots, screen recording and the preview in the app overview are blocked.
- `allowBackup="false"` plus `dataExtractionRules` that exclude everything: no Android cloud backup, no ADB backup and, from Android 12 on, no device-to-device transfer of the app data (`allowBackup` alone does not stop that transfer since Android 12; fixed in 1.45.42).
- Auto-lock when you leave the app: immediately, after 1 minute (default) or after 5 minutes. The key is dropped from memory on lock.
- Optional: wipe the vault after 5, 10 or 20 wrong passwords in a row (off by default). This is not forensic deletion.
- Copying text uses the clipboard marked as sensitive (Android 13 and newer) and clears it after 30 seconds, unless you copied something else in the meantime.
- Notifications never contain note text.
- Permissions: notifications, exact alarms (for reminders), run at boot (to re-arm alarms), biometrics. No internet, no camera, no storage permission.

## 6. What is stored unencrypted

Be aware of these. They contain times and settings, never note text, passwords or keys:

- Expiry, warning and reminder times of notes (needed so that Android can ring the alarm while the app is locked). This storage stays empty if you do not use reminders or expiry notifications.
- The time of the last password entry and of the last backup.
- Settings such as theme, language, lock delay and sort order.
- The approximate size of the vault, and the number and approximate size of stored images (IndexedDB keys and record sizes are visible; the image content is not).
- Whether a fingerprint code or a panic code (destruction code) is set up. The panic code is stored only as a salted PBKDF2 hash, but a code of 4 to 8 digits can be guessed offline by someone who has the app data.

One thing leaves the app unencrypted only on request: the plain-text export (Advanced) writes all notes, except images, drawings and read-and-gone notes, to a Markdown file you choose. It requires the password and a confirmation and does not count as a backup. Wherever you save it, it is readable by anyone with access to that place.

## 7. Known limitations

- No independent audit.
- Offline guessing: anyone who obtains the vault file (device image, backup file, backup folder) can try passwords without limit. Argon2id makes each guess expensive (64 MiB of memory), but a short or common password still falls. Backups from before 1.45.37 use PBKDF2 and are much cheaper to attack. The password decides; the "wipe after wrong passwords" option and the lock counters only act on the phone itself and do not slow down an attacker who works on a copy.
- The fingerprint code (4 to 8 digits) and the panic code protect against someone who forces you to use the app. They do not protect against an attacker who can extract the Keystore-protected data, because 4 to 8 digits are quickly guessed offline.
- The vault has no freshness check: someone who can write to the storage can put back an older copy of the vault. The header fields (`iter`, `salt`, `iv`) are not authenticated, but changing them only makes decryption fail.
- On a rooted or compromised device, or while the app is unlocked, malware can read what you can read.
- JavaScript strings cannot be reliably wiped from memory. The key is a non-extractable `CryptoKey`, but the decrypted notes live in memory while the app is unlocked.
- Expiry-based deletion is not forensic deletion: flash storage may keep old blocks, and older backup files still contain deleted notes.
- Automatic deletion only happens the next time the app is unlocked, because only then can Chiffre read the notes.
- The password cannot be reset. Forget it and the notes are gone. Make backups.

## 8. Verifying a build

Chiffre aims at builds that anyone can check against the source. Status: **reproducible**. For 1.45.41 a fresh clone of the tag, built on macOS without the signing key, gave an APK whose contents match the GitHub release byte for byte (signature excluded). Please check later releases yourself with the steps below.

1. Check out the release tag (`v<version>`) in a clean directory and build it (`./gradlew assembleRelease`, see [docs/RELEASE.md](RELEASE.md)).
2. Compare your APK with the one from the GitHub release with `tools/compare-apks.sh`. It ignores the signature (`META-INF`) and compares everything else byte by byte.
3. Check the signing certificate of the release APK with `apksigner verify --print-certs`. The SHA-256 fingerprint is in the [README](../README.md).

Differences that are not caused by the signature are a bug in the build setup. Please report them.
