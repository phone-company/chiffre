#!/bin/sh
# Prüft vor einem Release, ob alles zusammenpasst. Aufruf aus dem Projektordner:
#   tools/check-release.sh                      prüft den Ordner
#   tools/check-release.sh chiffre-update-X.zip prüft zusätzlich den Inhalt des Zips auf Geheimnisse
# Checks a release for consistency. Run from the project folder. Needs python3 (node and clang are optional).
cd "$(dirname "$0")/.." || exit 2
exec python3 - "$@" <<'PYEOF'
import base64, glob, hashlib, os, re, shutil, subprocess, sys, zipfile

bad, ok_n = [], 0
def check(name, cond, extra=""):
    global ok_n
    if cond: ok_n += 1; print("OK      " + name)
    else: bad.append(name); print("FEHLER  " + name + (("  [" + str(extra) + "]") if extra else ""))
def read(p): return open(p, encoding="utf-8").read()
def find(rx, text, grp=1):
    m = re.search(rx, text); return m.group(grp) if m else None

gradle = read("app/build.gradle.kts")
ver = find(r'versionName\s*=\s*"([^"]+)"', gradle); code = find(r'versionCode\s*=\s*(\d+)', gradle)
check("build.gradle.kts: Version und Code gefunden", ver and code, (ver, code))
html = read("app/src/main/assets/index.html")
check("index.html: ABOUT.version = " + str(ver), find(r'ABOUT = \{ version: "([^"]+)"', html) == ver, find(r'ABOUT = \{ version: "([^"]+)"', html))
fd = read("fdroid/app.chiffre.notes.yml")
check("fdroid: versionName, commit und CurrentVersion = " + str(ver), find(r'versionName:\s*(\S+)', fd) == ver and find(r'commit:\s*v(\S+)', fd) == ver and find(r'CurrentVersion:\s*(\S+)', fd) == ver)
check("fdroid: versionCode und CurrentVersionCode = " + str(code), find(r'versionCode:\s*(\d+)', fd) == code and find(r'CurrentVersionCode:\s*(\d+)', fd) == code)
for f in ("README.md", "README.de.md", "docs/RELEASE.md"):
    vs = set(re.findall(r'chiffre-(\d+\.\d+\.\d+)\.apk', read(f)))
    check(f + ": Dateiname der APK nennt " + str(ver), vs <= {ver}, sorted(vs))
fps = [find(r'([0-9A-F]{2}(?::[0-9A-F]{2}){31})', read(f)) for f in ("README.md", "README.de.md")]
check("README.md und README.de.md nennen denselben Zertifikat-Fingerabdruck", fps[0] is not None and fps[0] == fps[1], fps)
check("SECURITY.md nennt Argon2id und kein PBKDF2 als aktuelles Verfahren (Hinweis auf alte Tresore ausgenommen)", "Argon2id" in read("SECURITY.md") and "600.000 Runden) aus deinem Passwort" not in read("SECURITY.md"))
for lang in ("de-DE", "en-US"):
    p = "fastlane/metadata/android/%s/changelogs/%s.txt" % (lang, code)
    check("Changelog %s/%s.txt vorhanden, nicht leer, höchstens 500 Byte" % (lang, code), os.path.exists(p) and 0 < os.path.getsize(p) <= 500, os.path.getsize(p) if os.path.exists(p) else "fehlt")
sw = read("app/src/main/assets/sw.js")
_files = find(r'const FILES = \[([^\]]*)\]', sw) or ""
_miss = [f for f in sorted(os.listdir("app/src/main/assets")) if f != "sw.js" and f not in _files]
check("Service Worker: alle Dateien der Web-App stehen in FILES (offline nutzbar)", not _miss, _miss)
print("INFO    Cache-Name im Service Worker:", find(r'CACHE = "([^"]+)"', sw), "(bei Änderung an index.html hochzählen)")

# Argon2-Modul: das eingebettete ist das aus tools/argon2/
b64 = find(r'const ARGON_B64 = "([^"]+)"', html)
wasm = open("tools/argon2/argon2id.wasm", "rb").read()
check("Argon2: das in index.html eingebettete Modul ist tools/argon2/argon2id.wasm", b64 and base64.b64decode(b64) == wasm)
sha = hashlib.sha256(wasm).hexdigest()
for f in ("docs/SECURITY-DESIGN.md", "docs/SECURITY-DESIGN.de.md"):
    check(f + ": nennt die SHA-256-Summe des Moduls", sha in read(f), sha)
if shutil.which("clang") and "--rebuild" in sys.argv:
    subprocess.run(["sh", "tools/argon2/build.sh"], check=True, stdout=subprocess.DEVNULL)
    check("Argon2: Neubau aus argon2id.c ergibt dieselbe Datei", open("tools/argon2/argon2id.wasm", "rb").read() == wasm)

# JavaScript-Syntax
if shutil.which("node"):
    js = "\n;\n".join(re.findall(r'<script>([\s\S]*?)</script>', html))
    open("/tmp/_chiffre_check.js", "w", encoding="utf-8").write(js)
    r = subprocess.run(["node", "--check", "/tmp/_chiffre_check.js"], capture_output=True, text=True)
    check("index.html: JavaScript ist syntaktisch gültig", r.returncode == 0, r.stderr.strip()[:200])
else: print("INFO    node fehlt, Syntaxprüfung übersprungen")

# Versprechen: kein Internet
mf = read("app/src/main/AndroidManifest.xml")
perms = sorted(re.findall(r'uses-permission android:name="android\.permission\.([A-Z_]+)"', mf))
check("Manifest: nur die erwarteten Berechtigungen, kein INTERNET", perms == ["POST_NOTIFICATIONS", "RECEIVE_BOOT_COMPLETED", "SCHEDULE_EXACT_ALARM", "USE_BIOMETRIC", "USE_EXACT_ALARM"], perms)
check("Manifest: allowBackup=false, kein Klartext-Verkehr", 'allowBackup="false"' in mf and 'usesCleartextTraffic="false"' in mf)
net = re.findall(r'\b(fetch\(|XMLHttpRequest|WebSocket|EventSource|sendBeacon|importScripts|navigator\.serviceWorker\.register)', re.sub(r'/\*[\s\S]*?\*/|//[^\n]*', '', html))
check("index.html: keine Netzwerk-Aufrufe (nur eigener Service Worker erlaubt)", set(net) <= {"navigator.serviceWorker.register"}, sorted(set(net)))
ext = re.findall(r'<(?:script|link|img|iframe)[^>]+(?:src|href)="(https?://[^"]+)"', html)
check("index.html: keine fremden Skripte, Stile, Bilder oder Rahmen", not ext, ext[:3])
check("MainActivity: kein Internet-Zugriff im Java-Code", not re.search(r'HttpURLConnection|java\.net\.URL\b|OkHttp|Socket\(', read("app/src/main/java/app/chiffre/MainActivity.java")))
check("Content-Security-Policy: kein 'unsafe-eval' (nur 'wasm-unsafe-eval')", "'unsafe-eval'" not in html and "default-src 'none'" in html)

# Geheimnisse
forbidden = re.compile(r'(^|/)(keystore\.properties|local\.properties|[^/]*\.(jks|keystore|p12|pem|env))$')
pat = re.compile(rb'storePassword=(?!HIER)|keyPassword=(?!HIER)|BEGIN (RSA |EC )?PRIVATE KEY|ghp_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}')
tree = []
for root, dirs, files in os.walk("."):
    dirs[:] = [d for d in dirs if d not in (".git", "build", ".gradle", ".idea")]
    tree += [os.path.join(root, f)[2:] for f in files]
local = [f for f in tree if forbidden.search(f) and not f.endswith(".example")]
if local: print("INFO    lokale Schlüsseldateien im Ordner (bleiben aus Zip und Git, geprüft wird das Zip): " + ", ".join(local[:4]))
hits = [f for f in tree if f not in local and f != "tools/check-release.sh" and not f.endswith((".png", ".wasm", ".jar")) and pat.search(open(f, "rb").read())]
check("Ordner: keine Passwörter oder Schlüssel im Text", not hits, hits[:3])
for z in [a for a in sys.argv[1:] if a.endswith(".zip")]:
    zf = zipfile.ZipFile(z); names = zf.namelist()
    check(z + ": keine Schlüssel- oder Zugangsdateien", not [n for n in names if forbidden.search(n) and not n.endswith(".example")], [n for n in names if forbidden.search(n)][:3])
    zh = [n for n in names if n != "tools/check-release.sh" and not n.endswith((".png", ".wasm", ".jar")) and pat.search(zf.read(n))]
    check(z + ": keine Passwörter oder Schlüssel im Text", not zh, zh[:3])
    check(z + ": enthält Version " + str(ver), "app/build.gradle.kts" in names and ('versionName = "%s"' % ver).encode() in zf.read("app/build.gradle.kts"))

print("\n%d Prüfungen bestanden, %d Fehler" % (ok_n, len(bad)))
sys.exit(1 if bad else 0)
PYEOF
