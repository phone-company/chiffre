# Veröffentlichen: Schlüssel, APK, GitHub, F-Droid

Diese Anleitung ist für macOS mit Android Studio geschrieben.

## 1. Signaturschlüssel erzeugen (einmalig)

Android installiert ein Update nur, wenn es mit demselben Schlüssel signiert ist wie die installierte Version. Geht der Schlüssel verloren, müssen alle Nutzer deinstallieren und neu installieren. Ihre Notizen sind dann weg, wenn sie keine Sicherung haben.

```
mkdir -p ~/Schluessel/chiffre

"/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool" -genkeypair -v \
  -keystore ~/Schluessel/chiffre/chiffre-release.jks \
  -alias chiffre -keyalg RSA -keysize 4096 -validity 36500
```

- Wähle ein langes, einmaliges Passwort. Lege es in deinem Passwortmanager ab.
- Bei „Vor- und Nachname“ und den weiteren Fragen steht, was später im Zertifikat jeder APK sichtbar ist. Wenn du anonym bleiben willst, gib einen Projektnamen wie „Chiffre“ ein und lass den Rest leer (Eingabetaste).
- Moderne Java-Versionen legen eine PKCS12-Datei an. Dort sind Keystore-Passwort und Schlüsselpasswort dasselbe.

**Sichere die Datei `chiffre-release.jks` an mindestens zwei Orten**, zum Beispiel auf einem verschlüsselten USB-Stick und in deinem Passwortmanager als Anhang. Lade sie nie auf GitHub hoch und schicke sie niemandem.

Den Fingerabdruck brauchst du später für die README und für F-Droid:

```
"/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool" -list -v \
  -keystore ~/Schluessel/chiffre/chiffre-release.jks -alias chiffre | grep SHA256
```

## 2. Projekt öffnen und Schlüssel eintragen

1. Android Studio starten, **File > Open**, den Ordner `chiffre` wählen.
2. Warten, bis die Gradle-Synchronisierung fertig ist. Schlägt Android Studio eine Aktualisierung von Gradle oder dem Android-Plugin vor, nimm sie an. Ist Android Studio zu alt, gibt es unter **Help > Check for Updates** eine neuere Version.
3. Im Projektordner `keystore.properties.example` nach `keystore.properties` kopieren und ausfüllen. `storeFile` muss ein absoluter Pfad sein, zum Beispiel `/Users/dein-name/Schluessel/chiffre/chiffre-release.jks`. `keyPassword` ist bei einer PKCS12-Datei dasselbe wie `storePassword`.

## 3. Signierte APK bauen

Im Terminal im Projektordner:

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleRelease
cp app/build/outputs/apk/release/app-release.apk chiffre-1.22.2.apk
```

Prüfen, ob die APK signiert ist und mit welchem Zertifikat (der Pfad zu `apksigner` hängt von deiner Build-Tools-Version ab):

```
~/Library/Android/sdk/build-tools/<VERSION>/apksigner verify --print-certs chiffre-1.22.2.apk
```

Der angezeigte SHA-256-Wert muss zum Fingerabdruck aus Schritt 1 passen.

## 4. Auf das GrapheneOS-Gerät installieren

- Kopiere die APK per USB-Kabel auf das Handy (Modus „Dateiübertragung“) und öffne sie in der Dateien-App. GrapheneOS fragt, ob diese Quelle Apps installieren darf. Erlaube das nur für diesen Vorgang und nimm die Berechtigung danach unter **Einstellungen > Apps > Spezieller App-Zugriff > Unbekannte Apps installieren** wieder zurück.
- Oder per adb, wenn du USB-Debugging an hast: `adb install -r chiffre-1.22.2.apk`

## 5. Updates veröffentlichen

1. In `app/build.gradle.kts` `versionCode` um 1 erhöhen und `versionName` anpassen. Beide Werte bleiben wörtliche Zahlen und Texte. Dieselbe Versionsnummer steht auch in `app/src/main/assets/index.html` in der Konstante `ABOUT` (Anzeige unter Einstellungen > Über Chiffre).
2. Ein Eintrag in `fastlane/metadata/android/de-DE/changelogs/` und `en-US/changelogs/` mit dem Namen `<versionCode>.txt`.
3. Vor dem Update in der alten Version eine Sicherung anlegen.
4. Bauen wie in Schritt 3, über die alte Version installieren. Die Notizen bleiben erhalten, weil der Schlüssel derselbe ist.

## 6. Auf GitHub veröffentlichen

1. Auf github.com ein neues **öffentliches** Repository `chiffre` anlegen, ohne README.
2. Wenn du deine E-Mail-Adresse nicht in den Commits haben willst: auf GitHub unter **Settings > Emails** die Noreply-Adresse aktivieren und im Projekt eintragen:
   ```
   git config user.email "ID+BENUTZERNAME@users.noreply.github.com"
   git config user.name "BENUTZERNAME"
   ```
3. Im Projektordner:
   ```
   git init -b main
   git add .
   git status
   ```
   Prüfe die Liste. **Weder `keystore.properties` noch eine `.jks`-Datei dürfen darin auftauchen.** Die `.gitignore` schließt sie aus, aber kontrolliere es.
   ```
   git commit -m "Chiffre 1.22.2"
   git remote add origin https://github.com/BENUTZERNAME/chiffre.git
   git push -u origin main
   git tag v1.22.2
   git push origin v1.22.2
   ```
4. Auf GitHub unter **Releases > Draft a new release** den Tag `v1.22.2` wählen, `chiffre-1.22.2.apk` anhängen und den SHA-256-Fingerabdruck in die Beschreibung schreiben. Trage ihn auch in der `README.md` ein.
5. Optional, für die Browser-Version: **Settings > Pages > Source: GitHub Actions**. Der Workflow in `.github/workflows/pages.yml` veröffentlicht dann den Ordner `app/src/main/assets`.

Ob du die `applicationId` ändern willst, entscheide **vor dem ersten Release**. Sie steht in `app/build.gradle.kts`. Eine Änderung später macht aus der App eine neue, andere App. Üblich ist `io.github.BENUTZERNAME.chiffre`.

## 7. F-Droid

F-Droid baut die App selbst aus dem Quellcode. Vorgaben laut [F-Droid-Dokumentation](https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/): öffentliches Repository mit freier Lizenz, nur freie Abhängigkeiten, Versions-Tags, die zur `versionName` passen (`v1.22.2` bei `1.22.2`), und Metadaten im Repository. Das ist hier erfüllt: MIT-Lizenz, keine Abhängigkeiten, Tags nach dem Muster `v<versionName>`, Metadaten unter `fastlane/metadata/android`.

**Wichtige Entscheidung zur Signatur.** Android akzeptiert Updates nur vom gleichen Schlüssel.

- *Standard:* F-Droid signiert mit einem eigenen Schlüssel. Die F-Droid-App und deine GitHub-APK haben dann verschiedene Signaturen. Nutzer müssen bei einem Kanal bleiben, sonst müssen sie deinstallieren und neu installieren.
- *Reproduzierbarer Build:* F-Droid baut die App nach, vergleicht sie mit deiner signierten APK aus dem GitHub-Release und verteilt deine Signatur. Dann sind beide Kanäle austauschbar. Laut F-Droid ist das für neue Apps empfohlen, später lässt es sich schwer nachholen. Es ist aber aufwendiger, und ob dieser Build bei dir bitgleich herauskommt, lässt sich erst beim Versuch sagen.

Mein Vorschlag: Erst GitHub-Release und Obtainium nutzen und ausprobieren. Dann entscheiden, ob F-Droid den Aufwand wert ist.

**Antrag stellen:**

1. `fdroid/app.chiffre.notes.yml` aus diesem Projekt öffnen. Ersetze `BENUTZERNAME` und, falls du die `applicationId` geändert hast, den Dateinamen und die Zeile `Builds`.
2. Auf gitlab.com das Projekt `fdroid/fdroiddata` forken und einen Zweig mit dem Namen der `applicationId` anlegen.
3. Die Datei nach `metadata/<applicationId>.yml` kopieren.
4. Mit `fdroid lint` und `fdroid build` testen oder die CI des Forks laufen lassen.
5. Einen Merge Request mit dem Titel „New App: Chiffre“ stellen. Nach der Freigabe dauert es laut F-Droid meist 24 bis 48 Stunden, bis die App erscheint.

Für den reproduzierbaren Weg entfernst du in der Datei die Kommentarzeichen vor `Binaries` und `AllowedAPKSigningKeys` und trägst den Fingerabdruck in Kleinbuchstaben ohne Doppelpunkte ein.

Alternativ gibt es Drittanbieter-Repositories, die signierte APKs direkt aus GitHub-Releases übernehmen, zum Beispiel IzzyOnDroid. Das habe ich nicht geprüft, schau dort nach den aktuellen Bedingungen.
