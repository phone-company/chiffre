#!/usr/bin/env python3
"""Prüft die Oberfläche automatisch auf Kontrast (WCAG AA), fehlende Beschriftungen, zu kleine Tippflächen und seitwärts Überlauf,
im hellen, dunklen und dunklen OLED-Design und bei sehr großer Schrift. Voraussetzung: Playwright (Chromium) und ein Webserver auf
dem assets-Ordner:  cd app/src/main/assets && python3 -m http.server 8765  und dann  python3 tools/a11y-check.py
Checks contrast (WCAG AA), missing labels, small tap targets and horizontal overflow in light, dark, dark OLED and very large text."""
import json, sys, time
from playwright.sync_api import sync_playwright
PW="korrekt-pferd-batterie-klammer"
BASE = sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8765/index.html"
JS = r"""
() => {
  const parse = c => { const m = c.match(/rgba?\(([^)]+)\)/); if (!m) return null; const p = m[1].split(/[ ,\/]+/).filter(Boolean).map(Number); return { r: p[0], g: p[1], b: p[2], a: p.length > 3 ? p[3] : 1 }; };
  const lin = v => { v /= 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); };
  const L = c => 0.2126 * lin(c.r) + 0.7152 * lin(c.g) + 0.0722 * lin(c.b);
  const over = (f, b) => ({ r: f.r * f.a + b.r * (1 - f.a), g: f.g * f.a + b.g * (1 - f.a), b: f.b * f.a + b.b * (1 - f.a), a: 1 });
  const bgOf = el => { const stack = []; for (let e = el; e; e = e.parentElement) { const c = parse(getComputedStyle(e).backgroundColor); if (c && c.a > 0) { stack.push(c); if (c.a >= 1) break; } } let base = { r: 255, g: 255, b: 255, a: 1 }; if (!stack.length || stack[stack.length - 1].a < 1) { const dark = getComputedStyle(document.documentElement).colorScheme.includes("dark"); base = dark ? { r: 0, g: 0, b: 0, a: 1 } : base; } for (let i = stack.length - 1; i >= 0; i--) base = over(stack[i], base); return base; };
  const vis = el => { const r = el.getBoundingClientRect(); const cs = getComputedStyle(el); return r.width > 0 && r.height > 0 && cs.visibility !== "hidden" && cs.display !== "none" && parseFloat(cs.opacity) > 0.05 && !el.closest("[hidden]") && !el.closest("details:not([open]) > :not(summary)"); };
  const out = { low: [], noname: [], small: [], overflowX: document.documentElement.scrollWidth > innerWidth + 1 };
  const seen = new Set();
  for (const el of document.body.querySelectorAll("*")) {
    if (!vis(el)) continue;
    const own = [...el.childNodes].some(n => n.nodeType === 3 && n.textContent.trim().length > 0);
    if (own) {
      const cs = getComputedStyle(el), fg = parse(cs.color); if (!fg) continue;
      const bg = bgOf(el), f = fg.a < 1 ? over(fg, bg) : fg;
      const l1 = L(f), l2 = L(bg), ratio = (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05);
      const px = parseFloat(cs.fontSize), bold = parseInt(cs.fontWeight) >= 700, large = px >= 24 || (px >= 18.66 && bold);
      const need = large ? 3 : 4.5;
      if (ratio < need) { const key = el.tagName + "." + el.className + "|" + el.textContent.trim().slice(0, 24); if (!seen.has(key)) { seen.add(key); out.low.push({ el: el.tagName.toLowerCase() + (el.id ? "#" + el.id : "") + (el.className && typeof el.className === "string" ? "." + el.className.split(" ")[0] : ""), text: el.textContent.trim().slice(0, 30), ratio: Math.round(ratio * 100) / 100, need, px }); } }
    }
    if (el.matches("button, a[href], input:not([type=hidden]), select, textarea, summary, [role=button]")) {
      const name = (el.getAttribute("aria-label") || el.getAttribute("title") || el.textContent || "").trim() || (el.labels && el.labels.length ? el.labels[0].textContent.trim() : "") || (el.getAttribute("aria-labelledby") ? "x" : "") || el.getAttribute("placeholder") || "";
      if (!name) out.noname.push(el.tagName.toLowerCase() + (el.id ? "#" + el.id : "") + (typeof el.className === "string" && el.className ? "." + el.className.split(" ")[0] : ""));
      const r = el.getBoundingClientRect();
      if (el.matches("button, summary, [role=button]") && (r.width < 32 || r.height < 32)) out.small.push((el.id || el.className || el.tagName) + " " + Math.round(r.width) + "x" + Math.round(r.height));
    }
  }
  return out;
}
"""
def run(pg, label, res):
    r = pg.evaluate(JS); res.append((label, r))
with sync_playwright() as p:
    b = p.chromium.launch(); res = []
    for theme, oled, scheme in (("light", False, "light"), ("dark", False, "dark"), ("dark", True, "dark")):
        ctx = b.new_context(viewport={"width": 360, "height": 800}, locale="de-DE", color_scheme=scheme, reduced_motion="reduce")
        init = "try{localStorage.setItem('chiffre.theme','%s');%s localStorage.setItem('chiffre.mode','adv'); localStorage.setItem('chiffre.classicWrite','1');}catch(e){}" % (theme, "localStorage.setItem('chiffre.oled','1');" if oled else "")
        ctx.add_init_script(init); pg = ctx.new_page(); pg.goto(BASE)
        name = theme + ("+oled" if oled else "")
        run(pg, name + " | Sperrbildschirm", res)
        pg.fill("#pw", PW); pg.fill("#pw2", PW); pg.click("#lockSubmit")
        for _ in range(100):
            if pg.evaluate("key !== null"): break
            time.sleep(0.1)
        pg.evaluate("""() => { const n = Date.now(); for (const [t, c, e] of [['Rote Notiz #arbeit', 'red', 3600e3*3], ['Grüne Notiz', 'green', 864e5*2], ['Gelbe Notiz', 'yellow', 864e5*9], ['Blaue Notiz mit Erinnerung', 'blue', 864e5*20], ['Violett', 'violet', 864e5*40], ['Orange', 'orange', 864e5*3]]) notes.push({ id: uid(), text: t, created: n - 1000, expires: n + e, color: c, pinned: c === 'red' }); notes.push({ id: uid(), text: 'Abgelaufene Notiz', created: n - 9e6, expires: n - 1000 }); render(); }""")
        time.sleep(0.5)
        pg.evaluate("showPage('write')"); time.sleep(0.3); run(pg, name + " | Schreiben", res)
        pg.evaluate("showPage('notes')"); time.sleep(0.3); run(pg, name + " | Notizen", res)
        pg.click("#menuBtn") if pg.locator("#menuBtn").count() else None
        time.sleep(0.4)
        pg.evaluate("document.querySelectorAll('#menuSheet details').forEach(d => d.open = true)"); time.sleep(0.3)
        run(pg, name + " | Einstellungen (alles offen)", res)
        pg.evaluate("document.querySelectorAll('#menuSheet details').forEach(d => d.open = false)")
        ctx.close()
    # große Schrift
    for fs in ("xl",):
        ctx = b.new_context(viewport={"width": 360, "height": 800}, locale="de-DE", reduced_motion="reduce")
        ctx.add_init_script("try{localStorage.setItem('chiffre.fs','%s');localStorage.setItem('chiffre.mode','adv');localStorage.setItem('chiffre.classicWrite','1');}catch(e){}" % fs)
        pg = ctx.new_page(); pg.goto(BASE); run(pg, "Schrift " + fs + " | Sperrbildschirm", res)
        pg.fill("#pw", PW); pg.fill("#pw2", PW); pg.click("#lockSubmit")
        for _ in range(100):
            if pg.evaluate("key !== null"): break
            time.sleep(0.1)
        for pgname in ("write", "notes"):
            pg.evaluate("showPage('%s')" % pgname); time.sleep(0.3); run(pg, "Schrift " + fs + " | " + pgname, res)
        pg.click("#menuBtn") if pg.locator("#menuBtn").count() else None
        time.sleep(0.4); pg.evaluate("document.querySelectorAll('#menuSheet details').forEach(d => d.open = true)"); time.sleep(0.3)
        run(pg, "Schrift " + fs + " | Einstellungen (alles offen)", res); ctx.close()
    b.close()
for label, r in res:
    print("==", label, "| Kontrast-Mängel:", len(r["low"]), "| ohne Name:", len(r["noname"]), "| kleine Ziele:", len(r["small"]), "| Seitwärts-Überlauf:", r["overflowX"])
    for x in r["low"][:8]: print("   Kontrast", x)
    for x in sorted(set(r["noname"]))[:8]: print("   ohne Name", x)
    for x in sorted(set(r["small"]))[:6]: print("   klein", x)
bad = sum(len(r["low"]) + len(r["noname"]) + (1 if r["overflowX"] else 0) for _, r in res)
print("\nErgebnis:", "keine Mängel" if not bad else "%d Mängel" % bad)
sys.exit(1 if bad else 0)
