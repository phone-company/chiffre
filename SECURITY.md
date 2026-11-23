# Security

[English](#english) | [Deutsch](#deutsch)

## English

Chiffre is a one-person project and has not been reviewed by third parties. What the app protects and what it does not is explained in detail in the section ["What Chiffre protects and what it does not"](README.md#what-chiffre-protects-and-what-it-does-not) of the README. Here is the short version and the way to report issues. The technical design with test vectors and instructions for checking a build is in [docs/SECURITY-DESIGN.md](docs/SECURITY-DESIGN.md) ([Deutsch](docs/SECURITY-DESIGN.de.md)).

### Reporting a vulnerability

Please send an email to **chiffre@tuta.com** and describe what you found and how to reproduce it. Please do not publish details until I have had a chance to fix the problem. I answer as fast as I can, but expect a few days. There is no bug bounty. Credit in the release notes is welcome if you want it.

Bugs without a security impact can be reported as normal issues on GitHub.

### Supported versions

Only the latest version in [Releases](https://github.com/phone-company/chiffre/releases) is maintained. Before installing, compare the SHA-256 fingerprint of the signing certificate with the value in the README.

### What is protected

- All notes, drawings and images are stored encrypted in the app's private storage. Method: AES-256-GCM, key derived from your password with Argon2id (RFC 9106, 64 MiB of memory, 6 passes, 1 lane) through the official Argon2 reference implementation compiled to WebAssembly, Web Crypto API for the rest. Vaults created before version 1.45.37 used PBKDF2-SHA-256 (600,000 rounds) and are upgraded automatically the next time you unlock. The vault header (algorithm and parameters) is authenticated together with the data.
- The app has no internet permission. Data leaves the device only when you export a backup yourself.
- Screenshots and the preview in the app switcher are blocked. Android backup is switched off.
- Fingerprint (optional): Android remembers your password, encrypted with a key from the Android Keystore (in secure hardware on most devices). The key can only be used after a successful fingerprint with strong biometrics. If another fingerprint is added on the device, it becomes invalid and Chiffre asks for the password again. Someone who knows the device lock and enrolls their own finger does not get to the notes that way. Without the fingerprint feature nothing is remembered.
- Optional, in the "Advanced" feature set: a code after the fingerprint as a second step, and a panic code that wipes the vault immediately.

### What is not protected

- A password someone can guess. Anyone who has a copy of the data or of a backup file can try passwords offline as often as they like. The strength of your password decides. Chiffre requires at least 12 characters and shows an estimate when you create it. 5 to 6 random words are recommended. Argon2id makes each guess expensive, but it does not rescue a short or common password.
- Argon2id comes from the official reference implementation (unchanged). It matches two independent implementations on all test cases and is tested on every start. The app as a whole has not been audited.
- A device with malware, a keylogger or a malicious accessibility service.
- Coercion: someone who makes you put your finger on the sensor gets to the notes if the fingerprint is on. If that is a risk for you, turn the feature off or lock the app beforehand.
- The security of the fingerprint depends on the hardware and the Android version of your device. On devices without secure hardware the Keystore protects less. The password itself remains the real key and always works.
- The code after the fingerprint and the panic code have 4 to 8 digits. They do not stop someone who works on a copy of the data, because 4 to 8 digits are guessed quickly offline.
- Metadata: the expiry and reminder times of notes (only times, never text) are stored unencrypted in the app's private storage for the reminders. The approximate size of the vault is visible as well.
- After you enter it, the key stays in memory until the app locks.

### Changing the password

Under Settings > Change password, notes and images are re-encrypted with a new key. Older backup files stay readable with the old password. After a change, create a new backup and delete the old ones.

## Deutsch

Chiffre ist ein Ein-Personen-Projekt und wurde nicht von Dritten geprüft. Was die App schützt und was nicht, steht ausführlich im Abschnitt [„Was Chiffre schützt und was nicht“](README.de.md#was-chiffre-schützt-und-was-nicht) der README. Hier die Kurzfassung und der Weg für Meldungen. Den technischen Aufbau mit Testwerten und der Anleitung zum Prüfen eines Builds findest du in [docs/SECURITY-DESIGN.de.md](docs/SECURITY-DESIGN.de.md) ([English](docs/SECURITY-DESIGN.md)).

### Schwachstelle melden

Schreib bitte eine E-Mail an **chiffre@tuta.com** und beschreibe, was du gefunden hast und wie man es nachstellen kann. Bitte veröffentliche Einzelheiten erst, wenn ich Gelegenheit hatte, einen Fehler zu beheben. Ich antworte so schnell ich kann, rechne aber mit ein paar Tagen. Einen Bug-Bounty gibt es nicht. Dank in den Release-Hinweisen gibt es gern, wenn du das möchtest.

Fehler ohne Sicherheitsbezug kannst du ganz normal als Issue auf GitHub melden.

### Unterstützte Versionen

Gepflegt wird nur die jeweils neueste Version, die unter [Releases](https://github.com/phone-company/chiffre/releases) liegt. Prüfe vor der Installation den SHA-256-Fingerabdruck des Signaturzertifikats gegen den Wert in der README.

### Was geschützt wird

- Alle Notizen, Zeichnungen und Bilder liegen verschlüsselt im privaten Speicher der App. Verfahren: AES-256-GCM, Schlüssel per Argon2id (RFC 9106, 64 MiB Speicher, 6 Durchläufe, 1 Spur) aus deinem Passwort, über die offizielle, zu WebAssembly übersetzte Referenzimplementierung, für den Rest die Web Crypto API. Tresore, die vor Version 1.45.37 angelegt wurden, nutzten PBKDF2-SHA-256 (600.000 Runden) und werden beim nächsten Entsperren automatisch umgestellt. Der Kopf des Tresors (Verfahren und Einstellungen) wird zusammen mit den Daten beglaubigt.
- Die App hat keine Internet-Berechtigung. Daten verlassen das Gerät nur, wenn du selbst eine Sicherung exportierst.
- Screenshots und die Vorschau in der App-Übersicht sind blockiert. Das Android-Backup ist ausgeschaltet.
- Fingerabdruck (optional): Android merkt sich dein Passwort, verschlüsselt mit einem Schlüssel aus dem Android Keystore (bei den meisten Geräten in Sicherheits-Hardware). Der Schlüssel ist nur nach einem erfolgreichen Fingerabdruck mit starker Biometrie benutzbar. Kommt auf dem Gerät ein weiterer Fingerabdruck hinzu, wird er ungültig, und Chiffre fragt wieder nach dem Passwort. Wer das Gerät samt Gerätesperre kennt und einen eigenen Finger einträgt, kommt damit also nicht an die Notizen. Ohne die Fingerabdruck-Funktion wird nichts gemerkt.
- Optional, im Funktionsumfang „Erweitert“: ein Code nach dem Fingerabdruck als zweiter Schritt und ein Zerstörungscode, der den Tresor sofort löscht.

### Was nicht geschützt wird

- Ein Passwort, das jemand erraten kann. Wer eine Kopie der Daten oder einer Sicherungsdatei hat, kann das Passwort offline beliebig oft probieren. Die Stärke deines Passworts bestimmt die Sicherheit. Chiffre verlangt mindestens 12 Zeichen und zeigt beim Anlegen eine Einschätzung. Empfohlen sind 5 bis 6 zufällige Wörter. Argon2id macht jeden Versuch teuer, rettet aber kein kurzes oder gängiges Passwort.
- Argon2id stammt aus der offiziellen Referenzimplementierung (unverändert). Sie stimmt in allen Testfällen mit zwei unabhängigen Umsetzungen überein und wird bei jedem Start getestet. Die App als Ganzes wurde nicht geprüft.
- Ein Gerät mit Schadsoftware, Tastaturmitschnitt oder einem bösartigen Bedienungshilfe-Dienst.
- Zwang: Wer dich dazu bringt, deinen Finger aufzulegen, kommt mit eingeschaltetem Fingerabdruck an die Notizen. Wer das nicht riskieren will, schaltet die Funktion ab oder sperrt die App vorher.
- Die Sicherheit des Fingerabdrucks hängt an der Hardware und dem Android deines Geräts. Auf Geräten ohne Sicherheits-Hardware schützt der Keystore schwächer. Das Passwort selbst bleibt der eigentliche Schlüssel und funktioniert immer.
- Der Code nach dem Fingerabdruck und der Zerstörungscode haben 4 bis 8 Ziffern. Sie halten niemanden auf, der an einer Kopie der Daten arbeitet, denn 4 bis 8 Ziffern sind offline schnell erraten.
- Metadaten: Die Ablaufzeitpunkte und Erinnerungszeiten der Notizen (nur Uhrzeiten, nie Text) liegen für die Erinnerungen unverschlüsselt im privaten App-Speicher. Auch die ungefähre Größe des Tresors ist sichtbar.
- Nach der Eingabe liegt der Schlüssel im Arbeitsspeicher, bis die App sich sperrt.

### Passwort ändern

Unter Einstellungen > Passwort ändern werden Notizen und Bilder mit einem neuen Schlüssel neu verschlüsselt. Ältere Sicherungsdateien bleiben mit dem alten Passwort lesbar. Lege nach einer Änderung eine neue Sicherung an und lösche die alten.
