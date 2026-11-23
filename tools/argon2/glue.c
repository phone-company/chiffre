/* Chiffre: dünne Hülle um die offizielle Argon2-Referenzimplementierung (P-H-C/phc-winner-argon2, CC0/Apache-2.0).
   Hier steht kein Krypto-Code, nur: Speicherbereiche für Ein- und Ausgabe, der Aufruf von argon2_ctx und die
   paar Speicherfunktionen, die ein Build ohne C-Bibliothek braucht.
   Schnittstelle wie bisher: Passwort ab io_ptr(), Salz ab io_ptr()+4096, Ergebnis ab io_ptr()+4160.
   Der Arbeitsspeicher (m KiB) beginnt bei mem_base(); die Seite vergrößert den Speicher vorher passend. */
#include <stddef.h>
#include <stdint.h>
#include "argon2.h"

#define EXPORT __attribute__((visibility("default")))
extern unsigned char __heap_base;
static unsigned char IO[4352] __attribute__((aligned(16)));

EXPORT uint32_t io_ptr(void) { return (uint32_t)(uintptr_t)IO; }
EXPORT uint32_t mem_base(void) { return (((uint32_t)(uintptr_t)&__heap_base) + 15u) & ~15u; }

/* Die Referenz holt ihren Arbeitsspeicher über diese Rückrufe: genau ein Block ab mem_base(), kein malloc. */
static int taken = 0;
static int give_memory(uint8_t **memory, size_t bytes) {
    (void)bytes;
    if (taken) return ARGON2_MEMORY_ALLOCATION_ERROR;
    taken = 1; *memory = (uint8_t *)(uintptr_t)mem_base(); return ARGON2_OK;
}
static void take_back(uint8_t *memory, size_t bytes) { (void)memory; (void)bytes; taken = 0; }

/* Rückgabe 0 = ok, sonst Fehler. m in KiB. */
EXPORT int argon2id(uint32_t pwlen, uint32_t saltlen, uint32_t t, uint32_t m, uint32_t p, uint32_t outlen) {
    if (pwlen > 4096 || saltlen < 8 || saltlen > 64 || t < 1 || p < 1 || p > 64 || m < 8 * p || outlen < 4 || outlen > 64)
        return 1;
    argon2_context c;
    c.out = IO + 4160; c.outlen = outlen;
    c.pwd = IO; c.pwdlen = pwlen;
    c.salt = IO + 4096; c.saltlen = saltlen;
    c.secret = NULL; c.secretlen = 0;
    c.ad = NULL; c.adlen = 0;
    c.t_cost = t; c.m_cost = m; c.lanes = p; c.threads = 1;
    c.version = ARGON2_VERSION_13;
    c.allocate_cbk = give_memory; c.free_cbk = take_back;
    c.flags = ARGON2_DEFAULT_FLAGS;   /* das Passwort im Eingabebereich bleibt; die Seite löscht ihn danach selbst */
    int rc = argon2_ctx(&c, Argon2_id);
    taken = 0;
    return rc == ARGON2_OK ? 0 : 2;
}

/* Speicherfunktionen für den Build ohne C-Bibliothek */
void *memcpy(void *d, const void *s, size_t n) {
    unsigned char *a = d; const unsigned char *b = s;
    if ((((uintptr_t)a | (uintptr_t)b) & 7) == 0) for (; n >= 8; n -= 8, a += 8, b += 8) *(uint64_t *)a = *(const uint64_t *)b;
    while (n--) *a++ = *b++;
    return d;
}
void *memset(void *d, int c, size_t n) {
    unsigned char *a = d; uint64_t v = (unsigned char)c; v |= v << 8; v |= v << 16; v |= v << 32;
    while (n && ((uintptr_t)a & 7)) { *a++ = (unsigned char)c; n--; }
    for (; n >= 8; n -= 8, a += 8) *(uint64_t *)a = v;
    while (n--) *a++ = (unsigned char)c;
    return d;
}
int memcmp(const void *x, const void *y, size_t n) { const unsigned char *a = x, *b = y; for (; n; n--, a++, b++) if (*a != *b) return *a - *b; return 0; }
size_t strlen(const char *s) { size_t n = 0; while (s[n]) n++; return n; }
int strncmp(const char *a, const char *b, size_t n) { for (; n; n--, a++, b++) { if (*a != *b) return (unsigned char)*a - (unsigned char)*b; if (!*a) return 0; } return 0; }
void *malloc(size_t n) { (void)n; return NULL; }   /* nie gebraucht: der Speicher kommt über give_memory */
void free(void *p) { (void)p; }
