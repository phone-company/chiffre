# Veröffentlichen: Schlüssel, APK, GitHub, F-Droid

Für macOS mit Android Studio.

## 1. Signaturschlüssel erzeugen (einmalig)

Android installiert ein Update nur, wenn es mit demselben Schlüssel signiert ist wie die installierte Version. Geht er verloren, müssen alle Nutzer neu installieren, und ihre Notizen sind ohne Sicherung weg.

```
mkdir -p ~/Schluessel/chiffre

"/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool" -genkeypair -v \
  -keystore ~/Schluessel/chiffre/chiffre-release.jks \
  -alias chiffre -keyalg RSA -keysize 4096 -validity 36500
```

- Wähle ein langes, einmaliges Passwort. Lege es in deinem Passwortmanager ab.
- Die Antworten auf „Vor- und Nachname“ und die weiteren Fragen sind später im Zertifikat jeder APK sichtbar. Willst du anonym bleiben, gib „Chiffre“ ein und lass den Rest leer.
- Es entsteht eine PKCS12-Datei, Keystore- und Schlüsselpasswort sind dasselbe.

**Sichere die Datei `chiffre-release.jks` an mindestens zwei Orten**, zum Beispiel auf einem verschlüsselten USB-Stick und in deinem Passwortmanager als Anhang. Lade sie nie auf GitHub hoch und schicke sie niemandem.

Den Fingerabdruck brauchst du später für die README und für F-Droid:

```
"/Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool" -list -v \
  -keystore ~/Schluessel/chiffre/chiffre-release.jks -alias chiffre | grep SHA256
```

## 2. Projekt öffnen und Schlüssel eintragen

1. Android Studio starten, **File > Open**, den Ordner `chiffre` wählen.
2. Warten, bis die Gradle-Synchronisierung fertig ist. Schlägt Android Studio Updates für Gradle oder das Android-Plugin vor, nimm sie an.
3. Im Projektordner `keystore.properties.example` nach `keystore.properties` kopieren und ausfüllen. `storeFile` muss ein absoluter Pfad sein, zum Beispiel `/Users/dein-name/Schluessel/chiffre/chiffre-release.jks`. `keyPassword` ist bei einer PKCS12-Datei dasselbe wie `storePassword`.

## 3. Signierte APK bauen

Im Terminal im Projektordner:

```
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleRelease
cp app/build/outputs/apk/release/app-release.apk chiffre-1.45.44.apk
```

Prüfen, ob die APK signiert ist und mit welchem Zertifikat (der Pfad zu `apksigner` hängt von deiner Build-Tools-Version ab):

```
~/Library/Android/sdk/build-tools/<VERSION>/apksigner verify --print-certs chiffre-1.45.44.apk
```

Der angezeigte SHA-256-Wert muss zum Fingerabdruck aus Schritt 1 passen.

## 4. Auf das GrapheneOS-Gerät installieren

- Kopiere die APK per USB-Kabel auf das Handy (Modus „Dateiübertragung“) und öffne sie in der Dateien-App. GrapheneOS fragt, ob diese Quelle Apps installieren darf. Erlaube das nur für diesen Vorgang und nimm die Berechtigung danach unter **Einstellungen > Apps > Spezieller App-Zugriff > Unbekannte Apps installieren** wieder zurück.
- Oder per adb, wenn du USB-Debugging an hast: `adb install -r chiffre-1.45.44.apk`

## 5. Updates veröffentlichen

1. In `app/build.gradle.kts` `versionCode` um 1 erhöhen und `versionName` anpassen. Beide Werte bleiben wörtliche Zahlen und Texte. Dieselbe Versionsnummer steht auch in `app/src/main/assets/index.html` in der Konstante `ABOUT` (Anzeige unter Einstellungen > Über Chiffre).
2. Ein Eintrag in `fastlane/metadata/android/de-DE/changelogs/` und `en-US/changelogs/` mit dem Namen `<versionCode>.txt`.
3. Vor dem Update in der alten Version eine Sicherung anlegen.
4. Bauen wie in Schritt 3, über die alte Version installieren. Die Notizen bleiben erhalten, weil der Schlüssel derselbe ist.
5. Vor dem Veröffentlichen `tools/check-release.sh` laufen lassen (mit dem Zip als Argument, wenn du eines hast). Es prüft Versionsnummern an allen Stellen, die Changelogs, ob das Argon2-Modul in `index.html` dem aus `tools/argon2/` entspricht, das Versprechen „kein Internet“ und ob Schlüssel oder Passwörter im Paket stecken. Mit `--rebuild` baut es das Argon2-Modul zusätzlich neu und vergleicht. `tools/a11y-check.py` prüft Kontrast, Beschriftungen und große Schrift.

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
   git commit -m "Chiffre 1.45.44"
   git remote add origin https://github.com/BENUTZERNAME/chiffre.git
   git push -u origin main
   git tag v1.45.44
   git push origin v1.45.44
   ```
4. Auf GitHub unter **Releases > Draft a new release** den Tag `v1.45.44` wählen, `chiffre-1.45.44.apk` anhängen und den SHA-256-Fingerabdruck in die Beschreibung schreiben. Trage ihn auch in der `README.md` ein.
5. Optional, für die Browser-Version: **Settings > Pages > Source: GitHub Actions**. Der Workflow in `.github/workflows/pages.yml` veröffentlicht dann den Ordner `app/src/main/assets`.

Ob du die `applicationId` ändern willst, entscheide **vor dem ersten Release**. Sie steht in `app/build.gradle.kts`. Eine Änderung später macht aus der App eine neue, andere App. Üblich ist `io.github.BENUTZERNAME.chiffre`.

## 7. F-Droid

F-Droid baut die App aus dem Quellcode. Voraussetzungen laut [Quick Start Guide](https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/): öffentliches Repository, freie Lizenz, keine unfreien Abhängigkeiten, Tags nach dem Muster `v<versionName>` und Metadaten im Repository. Das ist hier erfüllt (MIT, keine Abhängigkeiten, `fastlane/metadata/android`).

**Signatur.** Android akzeptiert Updates nur vom selben Schlüssel.

- *Standard:* F-Droid signiert mit eigenem Schlüssel. GitHub-APK und F-Droid-App sind dann nicht austauschbar. Nutzer bleiben bei einem Kanal oder installieren neu.
- *Reproduzierbarer Build:* F-Droid baut nach, vergleicht mit deiner APK aus dem GitHub-Release und verteilt deine Signatur. Beide Kanäle sind austauschbar. Für neue Apps empfohlen, später kaum nachzuholen, aber aufwendiger. Ob dein Build bitgleich herauskommt, zeigt erst der Versuch.

**Antrag:**

1. `fdroid/app.chiffre.notes.yml` öffnen und `BENUTZERNAME` ersetzen (bei geänderter `applicationId` auch Dateiname und `Builds`).
2. Auf gitlab.com `fdroid/fdroiddata` forken und einen Zweig mit dem Namen der `applicationId` anlegen.
3. Datei nach `metadata/<applicationId>.yml` kopieren, mit `fdroid lint` und `fdroid build` prüfen (oder die CI des Forks nutzen).
4. Merge Request „New App: Chiffre“ stellen. Nach der Freigabe dauert es meist 24 bis 48 Stunden.

Für den reproduzierbaren Weg entfernst du in der Datei die Kommentarzeichen vor `Binaries` und `AllowedAPKSigningKeys` und trägst den Fingerabdruck klein und ohne Doppelpunkte ein.

Alternative: Drittanbieter-Repositories wie IzzyOnDroid übernehmen signierte APKs aus GitHub-Releases. Die aktuellen Bedingungen dort bitte selbst prüfen.
