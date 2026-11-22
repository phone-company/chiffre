/*
 * Argon2id (RFC 9106, version 0x13) für Chiffre, ohne Bibliotheken.
 * Argon2id (RFC 9106, version 0x13) for Chiffre, no libraries.
 *
 * Freestanding C für WebAssembly (wasm32). Kein Secret, keine zugehörigen Daten (ad),
 * ein Thread. Blake2b nach RFC 7693.
 * Freestanding C for WebAssembly (wasm32). No secret, no associated data, one thread.
 * Blake2b per RFC 7693.
 *
 * Bauen / Build: tools/argon2/build.sh
 * Prüfen / Verify: tools/argon2/verify.py (vergleicht mit libargon2 und python-cryptography)
 *
 * MIT-Lizenz wie der Rest von Chiffre.
 */
typedef unsigned char u8;
typedef unsigned int u32;
typedef unsigned long long u64;
#define EXPORT __attribute__((visibility("default")))

extern u8 __heap_base;

__attribute__((noinline)) void *memcpy(void *d, const void *s, unsigned long n) {
    u8 *a = (u8 *)d; const u8 *b = (const u8 *)s;
    while (n--) *a++ = *b++;
    return d;
}
__attribute__((noinline)) void *memset(void *d, int c, unsigned long n) {
    u8 *a = (u8 *)d;
    while (n--) *a++ = (u8)c;
    return d;
}

/* ---------- Blake2b ---------- */
static const u64 IV[8] = {
    0x6a09e667f3bcc908ULL, 0xbb67ae8584caa73bULL, 0x3c6ef372fe94f82bULL, 0xa54ff53a5f1d36f1ULL,
    0x510e527fade682d1ULL, 0x9b05688c2b3e6c1fULL, 0x1f83d9abfb41bd6bULL, 0x5be0cd19137e2179ULL};
static const u8 SIGMA[12][16] = {
    {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15},
    {14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3},
    {11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4},
    {7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8},
    {9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13},
    {2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9},
    {12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11},
    {13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10},
    {6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5},
    {10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0},
    {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15},
    {14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3}};

#define ROTR(x, n) (((x) >> (n)) | ((x) << (64 - (n))))

typedef struct { u64 h[8]; u64 t; u8 buf[128]; u32 n; u32 outlen; } B2;

static void b2_compress(B2 *s, const u8 *blk, int last) {
    u64 m[16], v[16];
    for (int i = 0; i < 16; i++) {
        u64 x = 0;
        for (int j = 7; j >= 0; j--) x = (x << 8) | blk[i * 8 + j];
        m[i] = x;
    }
    for (int i = 0; i < 8; i++) { v[i] = s->h[i]; v[i + 8] = IV[i]; }
    v[12] ^= s->t;
    if (last) v[14] = ~v[14];
#define G(a, b, c, d, x, y) \
    v[a] = v[a] + v[b] + (x); v[d] = ROTR(v[d] ^ v[a], 32); \
    v[c] = v[c] + v[d];       v[b] = ROTR(v[b] ^ v[c], 24); \
    v[a] = v[a] + v[b] + (y); v[d] = ROTR(v[d] ^ v[a], 16); \
    v[c] = v[c] + v[d];       v[b] = ROTR(v[b] ^ v[c], 63);
    for (int r = 0; r < 12; r++) {
        const u8 *sg = SIGMA[r];
        G(0, 4, 8, 12, m[sg[0]], m[sg[1]]);
        G(1, 5, 9, 13, m[sg[2]], m[sg[3]]);
        G(2, 6, 10, 14, m[sg[4]], m[sg[5]]);
        G(3, 7, 11, 15, m[sg[6]], m[sg[7]]);
        G(0, 5, 10, 15, m[sg[8]], m[sg[9]]);
        G(1, 6, 11, 12, m[sg[10]], m[sg[11]]);
        G(2, 7, 8, 13, m[sg[12]], m[sg[13]]);
        G(3, 4, 9, 14, m[sg[14]], m[sg[15]]);
    }
#undef G
    for (int i = 0; i < 8; i++) s->h[i] ^= v[i] ^ v[i + 8];
}
static void b2_init(B2 *s, u32 outlen) {
    for (int i = 0; i < 8; i++) s->h[i] = IV[i];
    s->h[0] ^= 0x01010000ULL ^ (u64)outlen;
    s->t = 0; s->n = 0; s->outlen = outlen;
}
static void b2_update(B2 *s, const u8 *in, u32 len) {
    for (u32 i = 0; i < len; i++) {
        if (s->n == 128) { s->t += 128; b2_compress(s, s->buf, 0); s->n = 0; }
        s->buf[s->n++] = in[i];
    }
}
static void b2_final(B2 *s, u8 *out) {
    s->t += s->n;
    for (u32 i = s->n; i < 128; i++) s->buf[i] = 0;
    b2_compress(s, s->buf, 1);
    for (u32 i = 0; i < s->outlen; i++) out[i] = (u8)(s->h[i >> 3] >> (8 * (i & 7)));
}
static void le32(u8 *o, u32 x) { o[0] = (u8)x; o[1] = (u8)(x >> 8); o[2] = (u8)(x >> 16); o[3] = (u8)(x >> 24); }
static void b2_u32(B2 *s, u32 x) { u8 b[4]; le32(b, x); b2_update(s, b, 4); }
static void blake2b(u8 *out, u32 outlen, const u8 *in, u32 inlen) {
    B2 s; b2_init(&s, outlen); b2_update(&s, in, inlen); b2_final(&s, out);
}

/* H' (variable Länge / variable length), RFC 9106 Abschnitt 3.3 */
static void hprime(u8 *out, u32 T, const u8 *in, u32 inlen) {
    B2 s;
    if (T <= 64) {
        b2_init(&s, T); b2_u32(&s, T); b2_update(&s, in, inlen); b2_final(&s, out);
        return;
    }
    u32 r = (T + 31) / 32 - 2;
    u8 v[64], w[64];
    b2_init(&s, 64); b2_u32(&s, T); b2_update(&s, in, inlen); b2_final(&s, v);
    for (int j = 0; j < 32; j++) out[j] = v[j];
    u32 pos = 32;
    for (u32 i = 2; i <= r; i++) {
        blake2b(w, 64, v, 64);
        for (int j = 0; j < 64; j++) v[j] = w[j];
        for (int j = 0; j < 32; j++) out[pos + j] = v[j];
        pos += 32;
    }
    blake2b(w, T - 32 * r, v, 64);
    for (u32 j = 0; j < T - 32 * r; j++) out[pos + j] = w[j];
}

/* ---------- Argon2 Füllfunktion / fill function ---------- */
static inline u64 fbla(u64 x, u64 y) { return x + y + 2ULL * (u64)(u32)x * (u64)(u32)y; }
#define GB(a, b, c, d) \
    a = fbla(a, b); d = ROTR(d ^ a, 32); c = fbla(c, d); b = ROTR(b ^ c, 24); \
    a = fbla(a, b); d = ROTR(d ^ a, 16); c = fbla(c, d); b = ROTR(b ^ c, 63);
#define PERM(v0, v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12, v13, v14, v15) \
    GB(v0, v4, v8, v12); GB(v1, v5, v9, v13); GB(v2, v6, v10, v14); GB(v3, v7, v11, v15); \
    GB(v0, v5, v10, v15); GB(v1, v6, v11, v12); GB(v2, v7, v8, v13); GB(v3, v4, v9, v14);

static void fill_block(const u64 *prev, const u64 *ref, u64 *next, int with_xor) {
    u64 R[128], T[128];
    for (int i = 0; i < 128; i++) { R[i] = ref[i] ^ prev[i]; T[i] = R[i]; }
    if (with_xor) for (int i = 0; i < 128; i++) T[i] ^= next[i];
    for (int i = 0; i < 8; i++) {
        u64 *v = R + 16 * i;
        PERM(v[0], v[1], v[2], v[3], v[4], v[5], v[6], v[7], v[8], v[9], v[10], v[11], v[12], v[13], v[14], v[15]);
    }
    for (int i = 0; i < 8; i++) {
        u64 *v = R + 2 * i;
        PERM(v[0], v[1], v[16], v[17], v[32], v[33], v[48], v[49], v[64], v[65], v[80], v[81], v[96], v[97], v[112], v[113]);
    }
    for (int i = 0; i < 128; i++) next[i] = T[i] ^ R[i];
}

/* ---------- Argon2id ---------- */
static u8 IO[4352];   /* 0..4095 Passwort, 4096..4159 Salt, 4160..4223 Ergebnis / password, salt, result */

EXPORT u32 io_ptr(void) { return (u32)(unsigned long)IO; }
EXPORT u32 mem_base(void) { return (((u32)(unsigned long)&__heap_base) + 15u) & ~15u; }

static void next_addresses(u64 *addr, u64 *input, const u64 *zero) {
    input[6]++;
    fill_block(zero, input, addr, 0);
    fill_block(zero, addr, addr, 0);
}

/* Rückgabe 0 = ok. m in KiB. Speicher ab mem_base() muss vorher >= (m/(4p))*4p KiB sein. */
EXPORT int argon2id(u32 pwlen, u32 saltlen, u32 t, u32 m, u32 p, u32 outlen) {
    if (pwlen > 4096 || saltlen < 8 || saltlen > 64 || t < 1 || p < 1 || p > 64 || m < 8 * p || outlen < 4 || outlen > 64)
        return 1;
    u32 mp = 4 * p * (m / (4 * p));
    u32 lane_len = mp / p, seg = lane_len / 4;
    u64 *mem = (u64 *)(unsigned long)mem_base();

    u8 h0[72]; B2 s;
    b2_init(&s, 64);
    b2_u32(&s, p); b2_u32(&s, outlen); b2_u32(&s, m); b2_u32(&s, t); b2_u32(&s, 0x13); b2_u32(&s, 2);
    b2_u32(&s, pwlen); b2_update(&s, IO, pwlen);
    b2_u32(&s, saltlen); b2_update(&s, IO + 4096, saltlen);
    b2_u32(&s, 0); b2_u32(&s, 0);
    b2_final(&s, h0);

    u8 blk[1024];
    for (u32 l = 0; l < p; l++) {
        for (u32 c = 0; c < 2; c++) {
            le32(h0 + 64, c); le32(h0 + 68, l);
            hprime(blk, 1024, h0, 72);
            u64 *dst = mem + ((u64)l * lane_len + c) * 128;
            for (int i = 0; i < 128; i++) {
                u64 x = 0;
                for (int j = 7; j >= 0; j--) x = (x << 8) | blk[i * 8 + j];
                dst[i] = x;
            }
        }
    }

    u64 zero[128], input[128], addr[128];
    for (int i = 0; i < 128; i++) zero[i] = 0;
    for (u32 pass = 0; pass < t; pass++) {
        for (u32 slice = 0; slice < 4; slice++) {
            int indep = (pass == 0 && slice < 2);
            for (u32 l = 0; l < p; l++) {
                if (indep) {
                    for (int i = 0; i < 128; i++) input[i] = 0;
                    input[0] = pass; input[1] = l; input[2] = slice; input[3] = mp; input[4] = t; input[5] = 2;
                }
                u32 start = 0;
                if (pass == 0 && slice == 0) { start = 2; if (indep) next_addresses(addr, input, zero); }
                u64 cur = (u64)l * lane_len + (u64)slice * seg + start;
                u64 prv = (cur % lane_len == 0) ? cur + lane_len - 1 : cur - 1;
                for (u32 i = start; i < seg; i++, cur++, prv++) {
                    if (cur % lane_len == 1) prv = cur - 1;
                    u64 rnd;
                    if (indep) { if (i % 128 == 0) next_addresses(addr, input, zero); rnd = addr[i % 128]; }
                    else rnd = mem[prv * 128];
                    u32 rl = (u32)((rnd >> 32) % p);
                    if (pass == 0 && slice == 0) rl = l;
                    int same = (rl == l);
                    u32 area;
                    if (pass == 0) {
                        if (slice == 0) area = i - 1;
                        else if (same) area = slice * seg + i - 1;
                        else area = slice * seg + (i == 0 ? (u32)-1 : 0);
                    } else {
                        if (same) area = lane_len - seg + i - 1;
                        else area = lane_len - seg + (i == 0 ? (u32)-1 : 0);
                    }
                    u64 rel = rnd & 0xFFFFFFFFULL;
                    rel = (rel * rel) >> 32;
                    rel = (u64)area - 1 - (((u64)area * rel) >> 32);
                    u32 st = 0;
                    if (pass != 0) st = (slice == 3) ? 0 : (slice + 1) * seg;
                    u64 abs = ((u64)st + rel) % lane_len;
                    fill_block(mem + prv * 128, mem + ((u64)rl * lane_len + abs) * 128, mem + cur * 128, pass != 0);
                }
            }
        }
    }

    u64 fin[128];
    for (int i = 0; i < 128; i++) fin[i] = mem[((u64)(lane_len - 1)) * 128 + i];
    for (u32 l = 1; l < p; l++)
        for (int i = 0; i < 128; i++) fin[i] ^= mem[((u64)l * lane_len + lane_len - 1) * 128 + i];
    for (int i = 0; i < 128; i++) for (int j = 0; j < 8; j++) blk[i * 8 + j] = (u8)(fin[i] >> (8 * j));
    hprime(IO + 4160, outlen, blk, 1024);

    /* Arbeitsspeicher löschen / wipe working memory */
    for (u64 i = 0; i < (u64)mp * 128; i++) mem[i] = 0;
    for (int i = 0; i < 1024; i++) blk[i] = 0;
    return 0;
}
