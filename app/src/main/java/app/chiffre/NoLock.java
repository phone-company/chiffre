package app.chiffre;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * "Ohne Passwort": Der Tresor bleibt mit Argon2id und AES-256-GCM verschlüsselt, nur kennt niemand das Passwort.
 * Es ist ein langes Zufallspasswort, das hier mit einem Schlüssel aus dem Android-Keystore verschlüsselt liegt. Der
 * Keystore-Schlüssel verlässt das Gerät nie und braucht keinen Fingerabdruck, die App öffnet also ohne Abfrage.
 *
 * Schutz: Wer die Dateien der App ausliest (Sicherung, Kopie des Speichers), kann ohne den Keystore dieses Geräts
 * nichts entschlüsseln. Wer das entsperrte Handy in der Hand hat, kann die Notizen lesen.
 *
 * Fächer: "vault" (Tresor-Passwort) und "backup" (Sicherungspasswort, das die Person selbst festgelegt hat).
 */
final class NoLock {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "chiffre_nolock";
    private static final String PREFS = "chiffre_nolock";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private NoLock() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static boolean validSlot(String slot) {
        return "vault".equals(slot) || "backup".equals(slot);
    }

    private static SecretKey key(boolean create) throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        KeyStore.Entry e = ks.getEntry(KEY_ALIAS, null);
        if (e instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) e).getSecretKey();
        }
        if (!create) {
            return null;
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        kg.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }

    /** Legt ein Geheimnis ab und prüft sofort, ob es sich wieder lesen lässt. */
    static boolean put(Context c, String slot, String secret) {
        if (!validSlot(slot) || secret == null || secret.isEmpty() || secret.length() > 1024) {
            return false;
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key(true));
            byte[] ct = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            boolean ok = prefs(c).edit()
                    .putString(slot + ".ct", Base64.encodeToString(ct, Base64.NO_WRAP))
                    .putString(slot + ".iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                    .commit();
            return ok && secret.equals(get(c, slot));
        } catch (Exception e) {
            return false;
        }
    }

    /** Das Geheimnis oder "" (nicht vorhanden oder nicht lesbar). */
    static String get(Context c, String slot) {
        if (!validSlot(slot)) {
            return "";
        }
        try {
            String ct = prefs(c).getString(slot + ".ct", null), iv = prefs(c).getString(slot + ".iv", null);
            SecretKey k = key(false);
            if (ct == null || iv == null || k == null) {
                return "";
            }
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(ct, Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /** Ein Fach leeren; ist danach keins mehr belegt, auch den Keystore-Schlüssel löschen. */
    static void clear(Context c, String slot) {
        if (!validSlot(slot)) {
            return;
        }
        SharedPreferences p = prefs(c);
        p.edit().remove(slot + ".ct").remove(slot + ".iv").commit();
        if (p.getString("vault.ct", null) == null && p.getString("backup.ct", null) == null) {
            try {
                KeyStore ks = KeyStore.getInstance(KEYSTORE);
                ks.load(null);
                ks.deleteEntry(KEY_ALIAS);
            } catch (Exception ignored) {
                // nichts zu löschen
            }
        }
    }
}
