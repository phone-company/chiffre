# Chiffre: Sicherheitsaufbau

[English](SECURITY-DESIGN.md) | [Deutsch](SECURITY-DESIGN.de.md)

Dieses Dokument beschreibt, wie Chiffre deine Notizen schützt, was es bewusst nicht schützt und wie du die Aussagen selbst überprüfen kannst. Chiffre ist ein Ein-Personen-Projekt und wurde **nicht unabhängig geprüft**. Alles hier ist so geschrieben, dass du es am Quellcode nachprüfen kannst (`app/src/main/assets/index.html` und `app/src/main/java/app/chiffre/`).

Schwachstellen melden: siehe [SECURITY.md](../SECURITY.md).

## 1. Was Chiffre schützen soll

Chiffre schützt **gespeicherte Notizen**, wenn jemand Zugriff auf das Gerät oder eine Kopie der App-Daten hat, aber dein Passwort nicht kennt. Mehr verspricht es nicht.

Angreifer:

| Angreifer | Geschützt? |
|---|---|
| Jemand, der das gesperrte Handy findet oder stiehlt | Ja, die Notizen sind verschlüsselt, der Schlüssel existiert nur bei entsperrter App |
| Jemand, der App-Daten oder eine Sicherungsdatei kopiert und Passwörter offline durchprobiert | Nur so stark wie dein Passwort (siehe 3) |
| Andere Apps auf demselben Handy | Ja, Android hält den App-Speicher privat; die App hat keine Internet-Berechtigung und gibt von sich aus nichts weiter |
| Schadsoftware auf dem entsperrten Gerät, Tastaturmitschnitt, bösartige Bedienungshilfen | **Nein** |
| Jemand, der dich zwingen kann, die App zu entsperren oder den Finger aufzulegen | **Nein** |
| Forensische Analyse von Arbeitsspeicher oder Speicher durch einen fähigen Angreifer | **Keine Garantie** |

## 2. Aufbau

- Die Android-App ist eine dünne Hülle (`MainActivity` und einige Hilfsklassen) um eine einzelne Webseite (`index.html`), die in einer Android-WebView läuft. Die Seite kommt aus den eigenen Assets der App unter der virtuellen Adresse `https://chiffre.invalid/`. Die Hülle blockiert alle anderen Anfragen, und die App hat keine `INTERNET`-Berechtigung.
- Die Verschlüsselung läuft mit der **Web Crypto API** (`crypto.subtle`) der WebView. Die einzige Ausnahme ist **Argon2id**: die offizielle Referenzimplementierung der Argon2-Autoren, unverändert, zu einem kleinen WebAssembly-Modul übersetzt, das in `index.html` steckt (Quelltext, Bauskript und Prüfung in `tools/argon2/`, siehe Abschnitt 3). Selbst geschriebenen Krypto-Code gibt es nicht.
- Dieselbe Seite läuft auch als Web-App (PWA) im Browser. Dieses Dokument betrifft die Android-App.

## 3. Kryptografie

| Punkt | Wert |
|---|---|
| Schlüsselableitung | **Argon2id** (RFC 9106): 64 MiB Speicher, 6 Durchläufe, 1 Spur, 32 Byte Ausgabe, zufälliges 16-Byte-Salz pro Tresor. Tresore mit 3 Durchläufen (Version 1.45.37 und 1.45.38) werden beim nächsten Entsperren automatisch auf 6 angehoben; ein stärkerer Tresor wird nie geschwächt. Tresore von vor Version 1.45.37 nutzten PBKDF2-HMAC-SHA-256 mit 600.000 Runden und werden beim nächsten Entsperren automatisch umgestellt. |
| Verschlüsselung | AES-256-GCM, zufälliger 12-Byte-IV bei jeder Verschlüsselung |
| Abgeleiteter Schlüssel | Nicht exportierbarer `CryptoKey` (aus JavaScript nicht auslesbar) |
| Passwortprüfung | Es wird nichts gespeichert. Ein falsches Passwort lässt die GCM-Prüfsumme scheitern. |
| Mindestlänge | 12 Zeichen (eine Stärke-Schätzung wird angezeigt, erzwungen wird nur die Länge) |

**Tresorformat.** Der Tresor liegt im `localStorage` der WebView unter dem Schlüssel `chiffre.vault` als JSON:

```
{ "v": 2, "kdf": "argon2id", "m": 65536, "t": 3, "p": 1, "salt": "<base64>", "iv": "<base64>", "ct": "<base64>" }
```

Das ältere Format (`v` 1) ist `{ "v": 1, "iter": 600000, "salt": …, "iv": …, "ct": … }` mit PBKDF2. Im Format 2 sind die Kopfwerte (`kdf`, `m`, `t`, `p`, `salt`) als zusätzliche beglaubigte Daten (AAD, `chiffre-vault-v2|kdf|m|t|p|salt`) an die AES-GCM-Verschlüsselung gebunden: Ändert jemand sie, schlägt die Entschlüsselung fehl.

`ct` ist die AES-GCM-Ausgabe (Chiffretext mit angehängter 16-Byte-Prüfsumme) des JSON-Arrays aller Notizen (Text, Titel, Etiketten, Farben, Zeichnungen, Zeitpunkte, Checklistenstatus). Das ganze Array wird als ein Block verschlüsselt und bei jedem Speichern mit neuem IV neu verschlüsselt.

**Bilder.** Bilder werden verkleinert (längste Seite 1280 px) und in IndexedDB (`chiffre-img`) abgelegt. Jedes Bild wird einzeln mit demselben Tresorschlüssel und eigenem zufälligem IV verschlüsselt. Zeichnungen gehören zur Notiz und liegen daher im Tresorblock.

**Passwort ändern.** Es wird ein neues zufälliges Salz erzeugt, Tresor und alle Bilder werden mit dem neuen Schlüssel neu verschlüsselt. Ältere Sicherungsdateien bleiben mit dem alten Passwort lesbar.

**Sicherungsdateien.** Eine Sicherungsdatei enthält den verschlüsselten Tresor und die verschlüsselten Bilder im selben Format. Sie ist genauso sicher wie der Tresor selbst.

**Zum Argon2id-Code.** Web Crypto kennt kein Argon2, deshalb nutzt Chiffre die **offizielle Argon2-Referenzimplementierung** der Argon2-Autoren ([P-H-C/phc-winner-argon2](https://github.com/P-H-C/phc-winner-argon2), CC0/Apache-2.0). Ihre Dateien liegen unverändert in `tools/argon2/ref/` (mit SHA-256-Summen in `ref/README.md`). Eine dünne Hülle, `tools/argon2/glue.c`, enthält keinen Krypto-Code: Sie stellt nur die Ein- und Ausgabebereiche bereit, übergibt der Referenz den Arbeitsspeicher und ruft `argon2_ctx` auf, dazu die paar Speicherfunktionen, die ein Build ohne C-Bibliothek braucht. Alles wird mit `clang --target=wasm32` zu einem Modul von etwa 12 KB ohne Importe übersetzt, das als Base64 in `index.html` steht. `tools/argon2/build.sh` baut es neu und gibt die SHA-256-Summe aus (`41edacc1ba806f2b5de866107ae19e5c662e9806371adf74027c7d36fce8845c` seit 1.45.47, 1.45.46 hatte denselben Code mit langsamerem Löschen des Speichers; 1.45.37 bis 1.45.45 nutzten eine selbst geschriebene Umsetzung mit identischen Ergebnissen). `tools/argon2/verify.py` vergleicht es mit zwei unabhängigen Umsetzungen (`libargon2` und Pythons `cryptography`) an 68 festen und zufälligen Fällen, auch mit mehreren Spuren und Ausgabelängen; alle Ergebnisse müssen gleich sein. Bei jedem Start führt Chiffre außerdem einen kurzen Test mit bekanntem Ergebnis aus und benutzt das Modul nicht, wenn er scheitert. Die Referenz überschreibt ihre 64 MiB Arbeitsspeicher nach jeder Ableitung mit Nullen, und für jede Ableitung wird eine frische WebAssembly-Instanz benutzt, damit der Speicher danach wieder frei wird. Die Content-Security-Policy erlaubt WebAssembly (`'wasm-unsafe-eval'`), aber weiterhin kein `eval` von JavaScript. Die Ableitung läuft in einem eigenen Thread (`argon-worker.js`, ein Web Worker aus den eigenen Dateien der App), damit die Oberfläche beim Entsperren nicht einfriert. Der Thread bekommt nur das übersetzte Modul, die Passwort-Bytes und das Salz, hat keinen Zugriff auf Speicher und wird danach beendet; er enthält denselben Rechenkern wie `index.html`, und ein kurzer Test mit bekanntem Ergebnis prüft beim ersten Gebrauch, ob sein Ergebnis stimmt. Ist der Thread nicht verfügbar oder schlägt er fehl, rechnet die Seite den Schlüssel selbst.

Seit 1.45.46 steckt in Chiffre kein selbst geschriebener Krypto-Code mehr: AES-GCM, SHA und PBKDF2 kommen aus Web Crypto, Argon2id aus der Referenzimplementierung. Die App als Ganzes hat kein Dritter geprüft.

**Grenzen der Schlüsselableitung.** Argon2id mit 64 MiB macht das Raten auf Grafikkarten deutlich teurer als PBKDF2, macht aber ein schwaches Passwort nicht stark. Dein Passwort entscheidet weiterhin. Empfohlen sind fünf bis sechs zufällige Wörter. Sicherungsdateien von vor 1.45.37 nutzen noch PBKDF2 und sind so leicht anzugreifen wie zuvor: ersetze sie durch neue Sicherungen.

### Testwert (Format 2, Argon2id)

Passwort `correct horse battery staple`, Salz-Bytes `00 01 02 … 0f`, IV-Bytes `a0 a1 … ab`, Argon2id mit 64 MiB, 3 Durchläufen, 1 Spur. Derselbe Klartext wie unten. Der abgeleitete Schlüssel (hex) ist:

```
0d1a3c6523c8f06e4e0af9c515aa5b5448cfebd6838f2d52c3d8b6ef8ddc3c2e
```

Der entstehende Tresor (zusätzliche beglaubigte Daten: `chiffre-vault-v2|argon2id|65536|3|1|AAECAwQFBgcICQoLDA0ODw==`) ist:

```json
{"v":2,"kdf":"argon2id","m":65536,"t":3,"p":1,"salt":"AAECAwQFBgcICQoLDA0ODw==","iv":"oKGio6Slpqeoqaqr","ct":"0Q36TRknoGQWv0axYZahDhJCwLeCXER9nNx2NEel0Tz4sKhGU/VZFM8LhK0OlYRNciRoFLCI+G3Ab/ZU9NA5QCNyO+44WtxseS2Q7l8g1VFTzhuhQZftYeGFl80lWl4NrUm0GA=="}
```

Dieser Wert wurde mit Pythons Paket `cryptography` erzeugt (`Argon2id` und `AESGCM`) und mit den eigenen Funktionen `keyFor` und `unseal` der App entschlüsselt; ändert man `m`, schlägt die Entschlüsselung fehl.

### Testwert (Format 1, PBKDF2)

Du kannst Format und Schlüsselableitung nachrechnen, ohne die App zu starten. Passwort `correct horse battery staple`, Salz-Bytes `00 01 02 … 0f`, IV-Bytes `a0 a1 … ab`, 600.000 Runden. Der Klartext ist:

```
[{"id":"n1","text":"Hallo Chiffre","created":1700000000000,"expires":1700600000000}]
```

Der abgeleitete Schlüssel (hex) ist:

```
ef177144eec9420cbc1093d2a8b344a92bc506d0d4ec9c028dd19f8324d8c1e6
```

Der daraus entstehende Tresor ist:

```json
{"v":1,"iter":600000,"salt":"AAECAwQFBgcICQoLDA0ODw==","iv":"oKGio6Slpqeoqaqr","ct":"8BWR6R8X/8pZGzCeJuFoy4x81RexfeXZNPJmgVdQR40347wM0DNBnjqeaIPoRxIY1qekOZ3sxOEVt2WJQ6l0c6T/IJdQLyW3C0fUeGO7Ke3K/qEI0Nt8tZTw3NqzE4oK2PPDYg=="}
```

Der Wert wurde mit dem Python-Paket `cryptography` erzeugt und mit den eigenen Funktionen `deriveKey` und `unseal` der App erfolgreich entschlüsselt. Zum Nachrechnen:

```python
import hashlib, json, base64
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

key = hashlib.pbkdf2_hmac("sha256", b"correct horse battery staple", bytes(range(16)), 600000, 32)
pt = b'[{"id":"n1","text":"Hallo Chiffre","created":1700000000000,"expires":1700600000000}]'
ct = AESGCM(key).encrypt(bytes(range(0xa0, 0xac)), pt, None)
print(key.hex()); print(base64.b64encode(ct).decode())
```

## 4. Entsperren per Fingerabdruck (optional)

- Standardmäßig aus. Schaltest du es ein, verschlüsselt Chiffre dein **Passwort** mit einem AES-256-GCM-Schlüssel im **Android Keystore** (Alias `chiffre_bio_v1`, bei den meisten Geräten in Sicherheits-Hardware). Das verschlüsselte Passwort und sein IV liegen in den privaten Einstellungen der App.
- Der Schlüssel verlangt bei jeder Benutzung eine Anmeldung mit **starker Biometrie** (Klasse 3) und wird **ungültig, sobald ein neuer Fingerabdruck hinzukommt**. Wer deine Geräte-PIN kennt und seinen eigenen Finger einträgt, kann ihn also nicht nutzen. Chiffre fragt dann wieder nach dem Passwort.
- Das Passwort funktioniert immer. Chiffre fragt außerdem in regelmäßigen Abständen danach (Voreinstellung alle 4 Wochen), damit du es nicht vergisst. Das ist eine Gedächtnisstütze und kein zusätzlicher Schutz.
- Ohne die Fingerabdruck-Funktion wird nichts gemerkt.
- **Nicht geschützt:** Wer deinen Finger auf den Sensor zwingen kann. Schalte die Funktion ab oder sperre die App vorher, wenn das zu deinem Risiko gehört.
- Die Sicherheit dieser Funktion hängt am Gerät. Ohne Sicherheits-Hardware schützt der Keystore weniger.

## 4a. Ohne Passwort (optional, nur in der Android-App)

- Beim Einrichten oder später unter Sicherheit wählbar. Chiffre öffnet sich dann ohne Abfrage.
- Der Tresor bleibt unverändert verschlüsselt (Argon2id, AES-256-GCM). Als Passwort dient ein Zufallswert aus 32 Byte (`crypto.getRandomValues`), den niemand kennt. Er liegt mit einem AES-256-GCM-Schlüssel im **Android Keystore** verschlüsselt (Alias `chiffre_nolock`, ohne Anmeldung nutzbar) in den privaten Einstellungen der App.
- **Geschützt:** Wer die Dateien der App ausliest (Kopie des Speichers, Sicherung des Geräts), kann ohne den Keystore dieses Geräts nichts entschlüsseln. Chiffre ist außerdem von der Cloud-Sicherung und der Übertragung auf ein neues Gerät ausgenommen.
- **Nicht geschützt:** Wer dein **entsperrtes Handy** in der Hand hat, kann die Notizen lesen. Gegen diesen Fall hilft nur ein Passwort.
- Was ein Passwort voraussetzt, ist in diesem Modus aus: Sperre, Fingerabdruck, Code nach dem Fingerabdruck, Zerstörungscode, Löschen nach Fehlversuchen.
- **Sicherungen** werden mit einem eigenen **Sicherungspasswort** verschlüsselt, das du festlegst (eigener Salt, Argon2id wie der Tresor). Es liegt ebenfalls im Keystore, damit der Sicherungsordner ohne Abfrage schreiben kann. Ohne Sicherungspasswort gibt es keine Sicherung, denn eine mit dem Zufallswert verschlüsselte Sicherung wäre auf einem anderen Gerät unbrauchbar.
- Der Wechsel zurück zu einem Passwort verschlüsselt Notizen und Bilder neu und löscht beide Einträge im Keystore. Der Wechsel zu „ohne Passwort“ verlangt das aktuelle Passwort.
- „Tresor löschen“ steht in diesem Modus unter Sicherheit > Ohne Passwort (mit Bestätigung durch das Wort LÖSCHEN). Es löscht Notizen, Bilder und beide Einträge im Keystore, danach beginnt Chiffre wie beim ersten Start.

## 5. Schutz zur Laufzeit in der Android-App

- `FLAG_SECURE`: Screenshots, Bildschirmaufnahmen und die Vorschau in der App-Übersicht sind gesperrt.
- `allowBackup="false"` und `dataExtractionRules`, die alles ausschließen: keine Android-Cloud-Sicherung, keine ADB-Sicherung und ab Android 12 auch keine Übertragung von Gerät zu Gerät (`allowBackup` allein verhindert diese Übertragung seit Android 12 nicht; behoben in 1.45.42).
- Automatische Sperre beim Verlassen: sofort, nach 1 Minute (Standard) oder nach 5 Minuten. Beim Sperren wird der Schlüssel aus dem Arbeitsspeicher entfernt.
- Optional: Tresor nach 5, 10 oder 20 falschen Passwörtern in Folge löschen (standardmäßig aus). Das ist kein forensisches Löschen.
- Beim Kopieren wird die Zwischenablage als vertraulich markiert (ab Android 13) und nach 30 Sekunden geleert, außer du hast zwischendurch etwas anderes kopiert.
- Benachrichtigungen enthalten nie Notiztext.
- Berechtigungen: Benachrichtigungen, genaue Alarme (für Erinnerungen), Start beim Hochfahren (um Alarme neu zu setzen), Biometrie. Kein Internet, keine Kamera, keine Speicherberechtigung.

## 6. Was unverschlüsselt liegt

Das solltest du wissen. Es enthält Zeiten und Einstellungen, nie Notiztext, Passwörter oder Schlüssel:

- Ablauf-, Vorwarn- und Erinnerungszeiten der Notizen (damit Android den Alarm auch bei gesperrter App auslösen kann). Dieser Speicher bleibt leer, wenn du weder Erinnerungen noch Ablauf-Benachrichtigungen nutzt.
- Der Zeitpunkt der letzten Passworteingabe und der letzten Sicherung.
- Die Symbole für Etiketten liegen mit dem Tresorschlüssel verschlüsselt (AES-256-GCM) im Speicher der App. Sie sind nicht Teil der Sicherung.
- Für Knöpfe in der Erinnerungsmeldung („In 15 Min.“, „Morgen früh“, „Erledigt“): die Zeitpunkte der gerade gemeldeten Erinnerungen und, bis zum nächsten Öffnen, Paare „alter Termin → neuer Zeitpunkt“. Keine Texte, keine Kennungen der Notizen.
- Einstellungen wie Design, Sprache, Sperrzeit und Sortierung.
- Die ungefähre Größe des Tresors sowie Anzahl und ungefähre Größe der gespeicherten Bilder (Schlüssel und Größen in IndexedDB sind sichtbar, der Bildinhalt nicht).
- Ob ein Code nach dem Fingerabdruck oder ein Zerstörungscode (Panikcode) eingerichtet ist. Der Zerstörungscode liegt nur als gesalzener PBKDF2-Hash vor, aber ein Code aus 4 bis 8 Ziffern lässt sich offline erraten, wenn jemand die App-Daten hat.

Nur auf ausdrücklichen Wunsch verlässt etwas die App unverschlüsselt: Der Klartext-Export (Erweitert) schreibt alle Notizen, außer Bildern, Zeichnungen und Lese-und-weg-Notizen, in eine Markdown-Datei an einem Ort deiner Wahl. Er verlangt Passwort und Bestätigung und zählt nicht als Sicherung. Wo du die Datei ablegst, kann jeder sie lesen, der dort Zugriff hat.

## 7. Bekannte Grenzen

- Keine unabhängige Prüfung.
- Offline raten: Wer die Tresordatei bekommt (Geräteabbild, Sicherungsdatei, Sicherungsordner), kann Passwörter unbegrenzt ausprobieren. Argon2id macht jeden Versuch teuer (64 MiB Speicher), aber ein kurzes oder gängiges Passwort fällt trotzdem. Sicherungen von vor 1.45.37 nutzen PBKDF2 und sind viel billiger anzugreifen. Das Passwort entscheidet. „Löschen nach falschen Passwörtern“ und die Sperrzähler wirken nur am Handy selbst und bremsen niemanden, der an einer Kopie arbeitet.
- Der Code nach dem Fingerabdruck (4 bis 8 Ziffern) und der Zerstörungscode schützen gegen jemanden, der dich zur Benutzung der App zwingt. Gegen einen Angreifer, der die durch den Keystore geschützten Daten herausholen kann, schützen sie nicht, denn 4 bis 8 Ziffern sind offline schnell erraten.
- Der Tresor hat keine Frischeprüfung: Wer in den Speicher schreiben kann, kann eine ältere Kopie des Tresors zurücklegen. Die Kopfwerte (`iter`, `salt`, `iv`) sind nicht beglaubigt, ihre Änderung lässt die Entschlüsselung aber nur fehlschlagen.
- Auf einem gerooteten oder befallenen Gerät und bei entsperrter App kann Schadsoftware lesen, was du lesen kannst.
- JavaScript-Zeichenketten lassen sich nicht zuverlässig aus dem Speicher löschen. Der Schlüssel ist ein nicht exportierbarer `CryptoKey`, die entschlüsselten Notizen liegen aber im Arbeitsspeicher, solange die App entsperrt ist.
- Das Löschen nach Ablauf ist kein forensisches Löschen: Flash-Speicher kann alte Blöcke behalten, und ältere Sicherungsdateien enthalten gelöschte Notizen weiter.
- Das automatische Löschen geschieht erst beim nächsten Entsperren, denn nur dann kann Chiffre die Notizen lesen.
- Das Passwort lässt sich nicht zurücksetzen. Wer es vergisst, kommt nicht mehr an die Notizen. Lege Sicherungen an.

## 8. Einen Build prüfen

Chiffre soll sich von jedem gegen den Quellcode prüfen lassen. Stand: **reproduzierbar**. Für 1.45.41 und 1.45.43 ergab ein frischer Clone des Tags, gebaut unter macOS ohne Signaturschlüssel, eine APK, deren Inhalt Byte für Byte mit der GitHub-Veröffentlichung übereinstimmt (Signatur ausgenommen). Spätere Versionen prüfst du am besten selbst mit den Schritten unten.

1. Den Release-Tag (`v<Version>`) in einem sauberen Ordner auschecken und bauen (`./gradlew assembleRelease`, siehe [docs/RELEASE.md](RELEASE.md)).
2. Deine APK mit der aus dem GitHub-Release vergleichen: `tools/compare-apks.sh`. Das Skript ignoriert die Signatur (`META-INF`) und vergleicht alles andere Byte für Byte.
3. Das Signaturzertifikat der Release-APK mit `apksigner verify --print-certs` prüfen. Der SHA-256-Fingerabdruck steht in der [README](../README.md).

Unterschiede, die nicht von der Signatur kommen, sind ein Fehler im Build-Aufbau. Bitte melde sie.
