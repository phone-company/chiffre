// Rechnet Argon2id abseits der Oberfläche, damit beim Entsperren nichts einfriert.
// Bekommt von der Seite das fertig übersetzte Modul, die Passwort-Bytes und das Salz und liefert 32 Bytes zurück.
// Kein Netz, kein Speicher, kein Zugriff auf Notizen. Der Rechenkern ist derselbe wie in index.html (argonRun); ein Test vergleicht beide.
"use strict";
function argonRun(mod, pwb, salt, m, t, p) {
  const x = new WebAssembly.Instance(mod, {}).exports;   // frische Instanz je Aufruf: der Speicher wird danach freigegeben
  const mp = 4 * p * Math.floor(m / (4 * p));
  const need = x.mem_base() + mp * 1024, have = x.memory.buffer.byteLength;
  if (need > have) x.memory.grow(Math.ceil((need - have) / 65536));
  const io = x.io_ptr(), u8 = new Uint8Array(x.memory.buffer);
  u8.fill(0, io, io + 4352); u8.set(pwb, io); u8.set(salt, io + 4096);
  if (x.argon2id(pwb.length, salt.length, t, m, p, 32) !== 0) throw new Error("argon2");
  const out = u8.slice(io + 4160, io + 4192);
  u8.fill(0, io, io + 4352);
  return out;
}
self.onmessage = e => {
  const d = e.data || {};
  let out = null;
  try {
    if (d.mod instanceof WebAssembly.Module && d.pwb instanceof Uint8Array && d.salt instanceof Uint8Array &&
        Number.isInteger(d.m) && Number.isInteger(d.t) && Number.isInteger(d.p)) out = argonRun(d.mod, d.pwb, d.salt, d.m, d.t, d.p);
  } catch (err) { out = null; }
  try { if (d.pwb && d.pwb.fill) d.pwb.fill(0); } catch (err) {}
  self.postMessage(out ? { ok: true, out } : { ok: false });
};
