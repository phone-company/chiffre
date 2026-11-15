# Sicherheit

Chiffre ist ein Ein-Personen-Projekt und wurde nicht von Dritten geprüft. Was die App schützt und was nicht, steht ausführlich im Abschnitt [„Was Chiffre schützt und was nicht“](README.md#was-chiffre-schützt-und-was-nicht) der README. Hier die Kurzfassung und der Weg für Meldungen.

## Schwachstelle melden

Schreib bitte eine E-Mail an **chiffre@tuta.com** und beschreibe, was du gefunden hast und wie man es nachstellen kann. Bitte veröffentliche Einzelheiten erst, wenn ich Gelegenheit hatte, einen Fehler zu beheben. Ich antworte so schnell ich kann, rechne aber mit ein paar Tagen. Einen Bug-Bounty gibt es nicht. Dank in den Release-Hinweisen gibt es gern, wenn du das möchtest.

Fehler ohne Sicherheitsbezug kannst du ganz normal als Issue auf GitHub melden.

## Unterstützte Versionen

Gepflegt wird nur die jeweils neueste Version, die unter [Releases](https://github.com/phone-company/chiffre/releases) liegt. Prüfe vor der Installation den SHA-256-Fingerabdruck des Signaturzertifikats gegen den Wert in der README.

## Was geschützt wird

- Alle Notizen, Zeichnungen und Bilder liegen verschlüsselt im privaten Speicher der App. Verfahren: AES-256-GCM, Schlüssel per PBKDF2-SHA-256 (600.000 Runden) aus deinem Passwort, Web Crypto API.
- Die App hat keine Internet-Berechtigung. Daten verlassen das Gerät nur, wenn du selbst eine Sicherung exportierst.
- Screenshots und die Vorschau in der App-Übersicht sind blockiert. Das Android-Backup ist ausgeschaltet.

## Was nicht geschützt wird

- Ein Passwort, das jemand erraten kann. Wer eine Kopie der Daten oder einer Sicherungsdatei hat, kann das Passwort offline beliebig oft probieren. Die Stärke deines Passworts bestimmt die Sicherheit. Chiffre verlangt mindestens 12 Zeichen und zeigt beim Anlegen eine Einschätzung. Empfohlen sind 5 bis 6 zufällige Wörter.
- Ein Gerät mit Schadsoftware, Tastaturmitschnitt oder einem bösartigen Bedienungshilfe-Dienst.
- Metadaten: Die Ablaufzeitpunkte und Erinnerungszeiten der Notizen (nur Uhrzeiten, nie Text) liegen für die Erinnerungen unverschlüsselt im privaten App-Speicher. Auch die ungefähre Größe des Tresors ist sichtbar.
- Nach der Eingabe liegt der Schlüssel im Arbeitsspeicher, bis die App sich sperrt.

## Passwort ändern

Unter Einstellungen > Passwort ändern werden Notizen und Bilder mit einem neuen Schlüssel neu verschlüsselt. Ältere Sicherungsdateien bleiben mit dem alten Passwort lesbar. Lege nach einer Änderung eine neue Sicherung an und lösche die alten.
