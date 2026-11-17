# Chiffre

Verschlüsselte Notizen mit Ablaufdatum. Jede Notiz bekommt beim Anlegen ein Ablaufdatum. Läuft es ab, fragt die App, ob die Notiz bleiben oder gelöscht werden soll. Pro Notiz lässt sich auch festlegen, dass sie nach dem Ablauf ohne Nachfrage gelöscht wird. So sammelt sich nicht jahrelang an, was längst erledigt ist.

- Verschlüsselt mit deinem Passwort (AES-256-GCM, Schlüsselableitung per PBKDF2-SHA-256 mit 600.000 Runden)
- Kein Konto, keine Cloud, keine Werbung, keine Analyse
- Die Android-App hat keine Internet-Berechtigung
- Optional: neutrale Benachrichtigungen („Eine Notiz ist abgelaufen“, bei automatischem Löschen vorher „Eine Notiz wird bald gelöscht“, Vorwarnzeit von 1 Stunde bis 1 Tag einstellbar), ohne Notiztext, jederzeit abschaltbar
- Erinnerung an einzelne Notizen (Datum und Uhrzeit): Die App meldet sich ohne Inhalt, die Notiz steht beim Öffnen ganz oben und lässt sich mit „Erledigt“ oder „+1 Std.“ weiterschieben. Auf Wunsch wiederholt sie sich täglich, wöchentlich oder monatlich („Erledigt“ springt dann zum nächsten Termin)
- Screenshots und die Vorschau in der App-Übersicht sind gesperrt
- Sperrt sich im Hintergrund: sofort, nach 1 oder nach 5 Minuten (einstellbar, Standard 1 Minute)
- Optional: Entsperren per Fingerabdruck, zusätzlich zum Passwort
- Verschlüsselte Sicherung als Datei, zum Wiederherstellen oder für den Umzug auf ein anderes Gerät, mit einstellbarer Erinnerung ans Sichern
- Optional: Tresor nach 5, 10 oder 20 falschen Passwörtern löschen (standardmäßig aus)
- Checklisten mit Kästchen: Erledigtes rutscht nach unten (abschaltbar), Unterpunkte durch Einrücken, Punkte lassen sich im Modus „Erweitert“ per Pfeiltasten verschieben, erledigte auf einmal löschen. Jede Notiz mit eigenem Titel, Etiketten per #Stichwort
- Suche in den Notizen (nur im entsperrten Tresor, der Suchtext wird nicht gespeichert) und, im Funktionsumfang „Erweitert“, auch innerhalb einer Notiz; „Rückgängig“ nach dem Löschen, Sortierung nach Ablauf, Neueste oder Farbe
- Funktionsumfang „Einfach“ oder „Erweitert“ (Einstellungen > Aussehen): Neue Tresore starten einfach, „Erweitert“ blendet zusätzliche Funktionen ein, etwa Suche in der Notiz, wiederkehrende Erinnerungen und die Pfeile für Unterpunkte in Checklisten. Bestehende Tresore behalten alles
- Zeichnungen mit dem Finger, auch ohne Text; verschlüsselt wie die Notiz
- Bilder an Notizen: aus der Galerie oder direkt aufgenommen, verkleinert und verschlüsselt
- Bereich „Läuft bald ab“ oben in den Notizen für alles, was in den nächsten 24 Stunden abläuft, deutlich markiert
- Notizen ohne Ablaufdatum möglich („Ohne Ablauf“)
- Kopieren-Knopf: Die Zwischenablage wird nach 30 Sekunden wieder geleert, Android blendet den Text dabei nicht in der Vorschau ein
- Hell und dunkel im Material-Stil. Ab Android 12 passen sich die Farben dem Hintergrundbild an (Material You). Die Oberfläche gibt es auf Deutsch und Englisch (folgt dem Handy, im Menü umstellbar)

## Installieren

- **Android:** APK unter [Releases](../../releases) herunterladen und installieren. Vorher den Fingerabdruck des Signaturzertifikats prüfen (siehe unten). Mit [Obtainium](https://github.com/ImranR98/Obtainium) lässt sich die App direkt aus den Releases installieren und aktuell halten: Obtainium installieren, dort „App hinzufügen“ wählen, `https://github.com/phone-company/chiffre` eintragen und bestätigen.
- **Browser:** Die gleiche App läuft als Web-App, siehe `app/src/main/assets`. Dort ist sie als installierbare Seite (PWA) aufgebaut.

**Fingerabdruck des Signaturzertifikats (SHA-256):**

```
30:62:23:26:8B:B1:1A:DF:8F:1C:16:6B:C1:CD:DB:27:3B:54:4E:F8:75:18:8A:BC:DA:EB:30:9D:ED:7A:FC:F9
```

Prüfen kannst du ihn mit `apksigner verify --print-certs chiffre-1.45.3.apk`. Dort steht derselbe Wert klein geschrieben und ohne Doppelpunkte:

```
306223268bb11adf8f1c166bc1cddb273b544ef875188abcdaeb309ded7afcf9
```

## Berechtigungen der Android-App

- **Benachrichtigungen:** Wird erst abgefragt, wenn du in der App unter Benachrichtigungen die Meldungen einschaltest.
- **Beim Start des Geräts ausführen:** Damit der Alarm nach einem Neustart wieder gestellt wird.
- **Alarme und Erinnerungen (USE_EXACT_ALARM):** Damit die Erinnerung zur genauen Zeit kommt. Ein ungenauer Alarm darf von Android um Stunden verspätet werden.
- **Biometrie (USE_BIOMETRIC):** Nur für das optionale Entsperren per Fingerabdruck.
- **Kamera:** keine Berechtigung nötig. „Foto“ startet die Kamera-App des Geräts. Sie schreibt in eine einzelne, kurzlebige Datei im privaten App-Cache (über einen nicht exportierten Provider). Das Foto landet nicht in der Galerie.
- **Internet:** keine. Die App kann nichts senden oder laden.

## Was Chiffre schützt und was nicht

Chiffre schützt gespeicherte Notizen, wenn jemand Zugriff auf das Gerät oder seinen Speicher hat, aber dein Passwort nicht kennt. Mehr verspricht es nicht. Meldungen zu Schwachstellen: siehe [SECURITY.md](SECURITY.md).

- Die Verschlüsselung nutzt Standardverfahren aus dem Android-WebView (WebCrypto). Der Code wurde nicht unabhängig geprüft.
- Das Passwort lässt sich nicht zurücksetzen. Wer es vergisst, kommt nicht mehr an die Notizen. Lege deshalb Sicherungen an.
- Die Sicherheit hängt am Passwort. PBKDF2 bremst Rateversuche nur begrenzt. Nimm eine lange Passphrase, zum Beispiel fünf zufällige Wörter. Neue Passwörter brauchen mindestens 12 Zeichen, eine Anzeige schätzt die Stärke. Unter Einstellungen > Passwort ändern vergibst du später ein neues; ältere Sicherungsdateien bleiben dann mit dem alten Passwort lesbar.
- Chiffre schützt nicht vor Schadsoftware auf dem entsperrten Gerät, vor Mitlesen am Bildschirm oder vor Tastaturmitschnitt.
- Bilder werden verkleinert (längste Seite 1280 Pixel) und einzeln mit demselben Schlüssel verschlüsselt im Speicher der App abgelegt, nicht im Tresor-Text. Das Original in deiner Galerie bleibt unverändert und unverschlüsselt, bis du es selbst löschst. Eine Sicherung als Datei enthält die Bilder, „Text kopieren“ nicht. Bis zu 12 Bilder pro Notiz, insgesamt 60.
- Das Ablaufdatum dient der Ordnung. Es ist kein forensisch sicheres Löschen.
- Das automatische Löschen geschieht erst beim nächsten Entsperren der App, denn nur dann kann Chiffre die Notizen lesen. Bis dahin liegt die Notiz weiter verschlüsselt auf dem Gerät. Eine automatisch gelöschte Notiz lässt sich nicht zurückholen, außer aus einer älteren Sicherung.
- Für die Erinnerung merkt sich die App die Ablaufzeitpunkte der Notizen (Ablauf, Vorwarnung und Erinnerung an eine Notiz, bei wiederholten Erinnerungen auch die nächsten Termine, nur Uhrzeiten, nie Text) unverschlüsselt im privaten App-Speicher. Ohne die Erinnerung bleibt dieser Speicher leer. Der Alarm ist genau, sofern Android das erlaubt.
- Nach der Eingabe liegt der Schlüssel im Arbeitsspeicher, bis die App sich sperrt.
- Mit Fingerabdruck merkt sich Android dein Passwort, verschlüsselt mit einem Schlüssel aus der Sicherheits-Hardware (Android Keystore). Der Schlüssel ist nur nach einem Fingerabdruck (starke Biometrie) nutzbar und wird ungültig, sobald auf dem Gerät ein Fingerabdruck hinzukommt. Wer dich zwingen kann, deinen Finger aufzulegen, kommt damit an die Notizen. Ohne Fingerabdruck-Funktion wird nichts gemerkt. Das Passwort funktioniert immer.
- Damit du das Passwort nicht vergisst, fragt Chiffre trotz Fingerabdruck regelmäßig danach: alle 4 Wochen (Voreinstellung), alle 2 Wochen, jede Woche oder nie. Das ist eine Gedächtnisstütze und kein zusätzlicher Schutz. Der Zeitpunkt der letzten Passwort-Eingabe steht unverschlüsselt im Speicher der App (nur die Uhrzeit, nie das Passwort).

## Bauen

Du brauchst Android Studio (aktuelle Version) oder ein JDK 17 oder neuer mit dem Android SDK.

```
./gradlew assembleRelease
```

Die APK liegt danach unter `app/build/outputs/apk/release/`. Ohne `keystore.properties` ist sie unsigniert. Wie du einen Schlüssel erzeugst und eine signierte APK baust, steht in [docs/RELEASE.md](docs/RELEASE.md).

Die App hat keine Abhängigkeiten außer dem Android-Plugin für Gradle. Der gesamte Code besteht aus einer Java-Klasse (`MainActivity.java`) und der Web-App in `app/src/main/assets/index.html`.

## Lizenz

MIT, siehe [LICENSE](LICENSE).
