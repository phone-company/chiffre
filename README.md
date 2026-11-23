# Chiffre

[English](README.md) | [Deutsch](README.de.md)

Encrypted notes with an expiry date. Every note gets an expiry date when you create it. When it passes, the app asks whether to keep or delete the note. You can also set a note to be deleted automatically after it expires, without asking. That way, what is long done does not pile up for years.

- Encrypted with your password (AES-256-GCM, key derived with Argon2id using 64 MiB of memory)
- No account, no cloud, no ads, no analytics
- The Android app has no internet permission
- Optional neutral notifications ("A note has expired", and before automatic deletion "A note will be deleted soon", warning time adjustable from 1 hour to 1 day) without note text, can be switched off at any time
- Reminders for single notes (date and time): the app notifies you without any content, the note sits at the top when you open the app and can be moved on with "Done" or "+1 h". If you want, it repeats daily, weekly or monthly ("Done" then jumps to the next date)
- Screenshots and the preview in the app switcher are blocked
- Locks itself in the background: immediately, after 1 or after 5 minutes (adjustable, default 1 minute)
- Optional: unlock with your fingerprint, in addition to the password. In the "Advanced" feature set you can add a code (4 to 8 digits) as a second step after the fingerprint
- Optional panic code (Advanced): a second code (4 to 8 digits) that deletes the vault immediately and without asking, if someone forces you to open the app
- Encrypted backup as a file, to restore or to move to another device, with an adjustable backup reminder. In the "Advanced" feature set the app can also write it to a folder of your choice when it locks (one file, no internet)
- Plain-text export (Advanced): all notes as one Markdown file for any text editor or other notes apps. It is not encrypted, so the app asks for your password and a confirmation first. Images, drawings and read-and-gone notes are left out
- Notes that delete themselves after reading (after closing or after one hour, "Advanced")
- Optional: wipe the vault after 5, 10 or 20 wrong passwords (off by default)
- Checklists with boxes: finished items move down (can be switched off), sub-items by indenting, items can be moved with arrow buttons in "Advanced", delete all finished items at once. Every note has its own title, tags with #keyword
- Search across notes (only in the unlocked vault, the search text is not stored) and, in "Advanced", also inside a note; "Undo" after deleting, sorting by expiry or latest edit
- Feature set "Simple" or "Advanced" (Settings > Appearance): new vaults start simple, "Advanced" shows additional features such as search inside a note, repeating reminders and the arrows for sub-items in checklists. Existing vaults keep everything
- Drawings with your finger, also without text; encrypted like the note
- Images on notes: from the gallery or taken directly, scaled down and encrypted
- "Expires soon" section at the top for everything that expires within the next 24 hours, clearly marked
- Notes without an expiry date are possible ("No expiry")
- Copy button: the clipboard is cleared again after 30 seconds, and Android does not show the text in the clipboard preview
- Light and dark in Material style, with true black for OLED screens if you like. From Android 12 the colors follow your wallpaper (Material You). The interface is available in German and English (follows the phone, can be changed in the menu)

## Install

- **Android:** Download the APK from [Releases](../../releases) and install it. Check the signing certificate fingerprint first (see below). With [Obtainium](https://github.com/ImranR98/Obtainium) you can install the app straight from the releases and keep it up to date: install Obtainium, choose "Add app", enter `https://github.com/phone-company/chiffre` and confirm.
- **Browser:** The same app runs as a web app, see `app/src/main/assets`. It is built there as an installable page (PWA).

Requires Android 8.0 (API 26) or newer.

**Signing certificate fingerprint (SHA-256):**

```
30:62:23:26:8B:B1:1A:DF:8F:1C:16:6B:C1:CD:DB:27:3B:54:4E:F8:75:18:8A:BC:DA:EB:30:9D:ED:7A:FC:F9
```

You can check it with `apksigner verify --print-certs chiffre-1.45.43.apk`. It shows the same value in lowercase and without colons:

```
306223268bb11adf8f1c166bc1cddb273b544ef875188abcdaeb309ded7afcf9
```

## Permissions of the Android app

- **Notifications:** Only asked for when you turn on notifications in the app.
- **Run at device startup:** So that the alarm is set again after a restart.
- **Alarms and reminders (USE_EXACT_ALARM):** So that the reminder arrives at the exact time. Android may delay an inexact alarm by hours.
- **Biometrics (USE_BIOMETRIC):** Only for the optional fingerprint unlock.
- **Camera:** no permission needed. "Photo" starts the device's camera app. It writes to a single short-lived file in the app's private cache (through a provider that is not exported). The photo does not end up in the gallery.
- **Internet:** none. The app cannot send or load anything.

## What Chiffre protects and what it does not

Chiffre protects stored notes when someone has access to the device or its storage but does not know your password. It promises nothing more. To report vulnerabilities, see [SECURITY.md](SECURITY.md). The technical design is in [docs/SECURITY-DESIGN.md](docs/SECURITY-DESIGN.md) ([Deutsch](docs/SECURITY-DESIGN.de.md)).

- The encryption uses standard procedures from the Android WebView (Web Crypto). The one exception is Argon2id, which Web Crypto does not offer: Chiffre contains its own implementation (about 250 lines of C, compiled to a small WebAssembly module). It was checked bit for bit against two independent implementations (libargon2 and Python's `cryptography`), and the app tests it on every start. None of this code has been independently audited.
- The password cannot be reset. If you forget it, you cannot get to the notes any more. So make backups.
- Security depends on the password. Argon2id makes guessing expensive, but it does not save a weak password. Use a long passphrase, for example five random words. New passwords need at least 12 characters, and an indicator estimates the strength. Under Settings > Change password you can set a new one later; older backup files then stay readable with the old password. Vaults created with an earlier version are upgraded to Argon2id automatically the next time you unlock.
- Chiffre does not protect against malware on the unlocked device, against someone watching your screen, or against keyloggers.
- Images are scaled down (longest side 1280 pixels) and stored individually, encrypted with the same key, in the app's storage, not inside the vault text. The original in your gallery stays unchanged and unencrypted until you delete it yourself. A backup file contains the images, "Copy text" does not. Up to 12 images per note, 60 in total.
- The expiry date is for tidiness. It is not forensically secure deletion.
- Automatic deletion only happens the next time you unlock the app, because only then can Chiffre read the notes. Until then the note stays on the device, encrypted. An automatically deleted note cannot be brought back, except from an older backup.
- For the reminders the app remembers the points in time of your notes (expiry, warning and reminder of a note; for repeating reminders also the next dates; only times, never text) unencrypted in the app's private storage. Without reminders this storage stays empty. The alarm is exact as far as Android allows it.
- After you enter it, the key stays in memory until the app locks.
- With fingerprint, Android remembers your password, encrypted with a key from the security hardware (Android Keystore). The key can only be used after a fingerprint (strong biometrics) and becomes invalid as soon as another fingerprint is added on the device. Someone who can force you to put your finger on the sensor gets to the notes that way. Without the fingerprint feature nothing is remembered. The password always works.
- The optional code after the fingerprint and the panic code have only 4 to 8 digits. They help against someone who forces you to use the app at the lock screen, but not against an attacker who works on a copy of the data: 4 to 8 digits are guessed quickly offline.
- So that you do not forget your password, Chiffre asks for it regularly despite the fingerprint: every 4 weeks (default), every 2 weeks, every week or never. This is a memory aid, not extra protection. The time of the last password entry is stored unencrypted in the app's storage (only the time, never the password).

## Build

You need Android Studio (current version) or a JDK 17 or newer with the Android SDK.

```
./gradlew assembleRelease
```

The APK is then in `app/build/outputs/apk/release/`. Without `keystore.properties` it is unsigned. How to create a key and build a signed APK is described in [docs/RELEASE.md](docs/RELEASE.md) (German).

The app has no dependencies other than the Android plugin for Gradle. The web app lives in a single file, `app/src/main/assets/index.html`, plus a small helper file for Argon2id, `argon-worker.js`. The Android shell consists of a few Java classes under `app/src/main/java/app/chiffre/`: `MainActivity` (window and bridge to the web app), `Biometrics` (fingerprint), `Reminders` and `ReminderReceiver` (alarms and notifications) and `CaptureProvider` (camera photo).

To check that an APK matches the source, see section 8 of the [security design](docs/SECURITY-DESIGN.md) and `tools/compare-apks.sh`. `tools/check-release.sh` checks a release for consistency before it is published.

## License

MIT, see [LICENSE](LICENSE).
