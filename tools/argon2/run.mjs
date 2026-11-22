// Hilfsskript für verify.py: liest Testfälle (JSON) von stdin, rechnet sie mit argon2id.wasm, schreibt Ergebnisse (hex) nach stdout.
import fs from "node:fs";
const wasm = fs.readFileSync(new URL("./argon2id.wasm", import.meta.url));
const { instance } = await WebAssembly.instantiate(wasm, {});
const x = instance.exports;
const cases = JSON.parse(fs.readFileSync(0, "utf8"));
const out = [];
for (const c of cases) {
  const pw = Buffer.from(c.pw, "hex"), salt = Buffer.from(c.salt, "hex");
  const mp = 4 * c.p * Math.floor(c.m / (4 * c.p));
  const need = x.mem_base() + mp * 1024;
  const have = x.memory.buffer.byteLength;
  if (need > have) x.memory.grow(Math.ceil((need - have) / 65536));
  const io = x.io_ptr(), u8 = new Uint8Array(x.memory.buffer);
  u8.fill(0, io, io + 4352);
  u8.set(pw, io); u8.set(salt, io + 4096);
  const rc = x.argon2id(pw.length, salt.length, c.t, c.m, c.p, c.len);
  out.push(rc === 0 ? Buffer.from(new Uint8Array(x.memory.buffer, io + 4160, c.len)).toString("hex") : "ERR" + rc);
}
console.log(JSON.stringify(out));
