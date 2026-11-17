package app.chiffre;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.CancellationSignal;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.UnrecoverableKeyException;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Entsperren per Fingerabdruck.
 *
 * Das Passwort wird mit einem Schlüssel aus dem Android Keystore verschlüsselt. Dieser Schlüssel
 * liegt in der Sicherheits-Hardware, ist nur nach einem erfolgreichen Fingerabdruck (starke
 * Biometrie) benutzbar und wird ungültig, sobald auf dem Gerät ein Fingerabdruck dazukommt.
 * Das Passwort selbst funktioniert immer weiter.
 */
final class Biometrics {

    static final int KIND_CANCELED = 0;
    static final int KIND_ERROR = 1;
    static final int KIND_INVALID = 2;
    static final int KIND_LOCKOUT = 3;

    static final int STATUS_UNSUPPORTED = 0;
    static final int STATUS_NOT_ENROLLED = 1;
    static final int STATUS_READY = 2;

    interface Callback {
        /** Beim Einrichten ist data leer, beim Entsperren das Passwort. */
        void onSuccess(byte[] data);

        /** detail ist ein kurzer Fehlercode für die Anzeige, z. B. "E7" oder "init:KeyStoreException". */
        void onFailure(int kind, String detail);
    }

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "chiffre_bio_v1";
    private static final String PREFS = "chiffre_bio";
    private static final String K_BLOB = "blob";
    private static final String K_IV = "iv";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private Biometrics() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean isEnabled(Context c) {
        return prefs(c).getString(K_BLOB, null) != null && prefs(c).getString(K_IV, null) != null;
    }

    /** Löscht Schlüssel und gemerktes Passwort. */
    static void clear(Context c) {
        prefs(c).edit().remove(K_BLOB).remove(K_IV).apply();
        try {
            KeyStore ks = KeyStore.getInstance(KEYSTORE);
            ks.load(null);
            ks.deleteEntry(KEY_ALIAS);
        } catch (Exception ignored) {
            // nichts zu löschen
        }
    }

    static int status(Context c) {
        if (Build.VERSION.SDK_INT < 28) {
            return STATUS_UNSUPPORTED;
        }
        if (Build.VERSION.SDK_INT >= 29) {
            BiometricManager bm = c.getSystemService(BiometricManager.class);
            if (bm == null) {
                return STATUS_UNSUPPORTED;
            }
            int r;
            if (Build.VERSION.SDK_INT >= 30) {
                r = bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG);
            } else {
                r = bm.canAuthenticate();
            }
            if (r == BiometricManager.BIOMETRIC_SUCCESS) {
                return STATUS_READY;
            }
            if (r == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED) {
                return STATUS_NOT_ENROLLED;
            }
            return STATUS_UNSUPPORTED;
        }
        return c.getPackageManager().hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)
                ? STATUS_READY : STATUS_UNSUPPORTED;
    }

    private static SecretKey createKey(boolean unlockedRequired) throws GeneralSecurityException {
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        KeyGenParameterSpec.Builder b = new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true);
        if (Build.VERSION.SDK_INT >= 30) {
            b.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG);
        } else {
            b.setUserAuthenticationValidityDurationSeconds(-1);
        }
        if (unlockedRequired && Build.VERSION.SDK_INT >= 28) {
            b.setUnlockedDeviceRequired(true);
        }
        kg.init(b.build());
        return kg.generateKey();
    }

    private static boolean deviceLocked(Context c) {
        try {
            KeyguardManager km = (KeyguardManager) c.getSystemService(Context.KEYGUARD_SERVICE);
            return km != null && km.isDeviceLocked();
        } catch (Exception e) {
            return false;
        }
    }

    private static String describe(Throwable e) {
        String s = e.getClass().getSimpleName();
        Throwable c = e.getCause();
        if (c != null && c != e) {
            s += "/" + c.getClass().getSimpleName();
        }
        return s;
    }

    private static SecretKey loadKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        return (SecretKey) ks.getKey(KEY_ALIAS, null);
    }

    /** Richtet den Fingerabdruck ein: Schlüssel anlegen, Fingerabdruck abfragen, Passwort verschlüsseln. */
    static void enable(Activity a, byte[] secret, Callback cb) {
        if (Build.VERSION.SDK_INT < 28) {
            cb.onFailure(KIND_ERROR, "api");
            return;
        }
        final Cipher cipher;
        String step = "key";
        try {
            clear(a);
            SecretKey key;
            try {
                key = createKey(true);
            } catch (Exception first) {
                // Manche Geräte lehnen "nur bei entsperrtem Gerät" ab. Der Schlüssel braucht auch dann
                // bei jeder Benutzung einen Fingerabdruck.
                clear(a);
                key = createKey(false);
            }
            step = "init";
            cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key);
        } catch (Exception e) {
            clear(a);
            Arrays.fill(secret, (byte) 0);
            cb.onFailure(KIND_ERROR, step + ":" + describe(e));
            return;
        }
        prompt(a, a.getString(R.string.bio_title_enable), cipher, new Callback() {
            @Override
            public void onSuccess(byte[] unused) {
                String detail = "";
                try {
                    byte[] ct = cipher.doFinal(secret);
                    prefs(a).edit()
                            .putString(K_BLOB, Base64.encodeToString(ct, Base64.NO_WRAP))
                            .putString(K_IV, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                            .commit();
                    if (!isEnabled(a)) {
                        throw new IllegalStateException("prefs");
                    }
                    cb.onSuccess(new byte[0]);
                    return;
                } catch (Exception e) {
                    detail = "seal:" + describe(e);
                    clear(a);
                } finally {
                    Arrays.fill(secret, (byte) 0);
                }
                cb.onFailure(KIND_ERROR, detail);
            }

            @Override
            public void onFailure(int kind, String detail) {
                clear(a);
                Arrays.fill(secret, (byte) 0);
                cb.onFailure(kind, detail);
            }
        });
    }

    /** Fragt den Fingerabdruck ab und liefert bei Erfolg das gemerkte Passwort. */
    static void unlock(Activity a, Callback cb) {
        if (Build.VERSION.SDK_INT < 28 || !isEnabled(a)) {
            cb.onFailure(KIND_ERROR, "na");
            return;
        }
        if (deviceLocked(a)) {
            // Der Schlüssel ist bei gesperrtem Gerät nicht benutzbar. Das ist kein Defekt: still bleiben,
            // nichts löschen. Der Knopf "Mit Fingerabdruck entsperren" geht später wieder.
            cb.onFailure(KIND_CANCELED, "");
            return;
        }
        final Cipher cipher;
        final byte[] blob;
        try {
            SecretKey key = loadKey();
            if (key == null) {
                clear(a);
                cb.onFailure(KIND_INVALID, "nokey");
                return;
            }
            byte[] iv = Base64.decode(prefs(a).getString(K_IV, ""), Base64.NO_WRAP);
            blob = Base64.decode(prefs(a).getString(K_BLOB, ""), Base64.NO_WRAP);
            cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
        } catch (KeyPermanentlyInvalidatedException | UnrecoverableKeyException e) {
            // Android hat den Schlüssel ungültig gemacht (neuer Fingerabdruck, geänderte Displaysperre)
            clear(a);
            cb.onFailure(KIND_INVALID, "");
            return;
        } catch (IllegalArgumentException e) {
            // gemerkte Daten beschädigt
            clear(a);
            cb.onFailure(KIND_INVALID, "data");
            return;
        } catch (Exception e) {
            // Alles andere kann vorübergehend sein. Schlüssel und Passwort bleiben erhalten.
            cb.onFailure(KIND_ERROR, "init:" + describe(e));
            return;
        }
        prompt(a, a.getString(R.string.bio_title_unlock), cipher, new Callback() {
            @Override
            public void onSuccess(byte[] unused) {
                try {
                    cb.onSuccess(cipher.doFinal(blob));
                } catch (Exception e) {
                    clear(a);
                    cb.onFailure(KIND_INVALID, "final:" + describe(e));
                }
            }

            @Override
            public void onFailure(int kind, String detail) {
                cb.onFailure(kind, detail);
            }
        });
    }

    private static void prompt(Activity a, String title, Cipher cipher, Callback cb) {
        if (Build.VERSION.SDK_INT < 28) {
            cb.onFailure(KIND_ERROR, "api");
            return;
        }
        BiometricPrompt.Builder builder = new BiometricPrompt.Builder(a)
                .setTitle(title)
                .setNegativeButton(a.getString(R.string.bio_cancel), a.getMainExecutor(),
                        (dialog, which) -> cb.onFailure(KIND_CANCELED, ""));
        if (Build.VERSION.SDK_INT >= 30) {
            builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG);
        }
        BiometricPrompt prompt = builder.build();
        prompt.authenticate(new BiometricPrompt.CryptoObject(cipher), new CancellationSignal(),
                a.getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                        cb.onSuccess(new byte[0]);
                    }

                    @Override
                    public void onAuthenticationError(int errorCode, CharSequence errString) {
                        // Der Knopf "Abbrechen" ruft den eigenen Listener auf, nicht diese Methode.
                        if (errorCode == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED
                                || errorCode == BiometricPrompt.BIOMETRIC_ERROR_CANCELED) {
                            cb.onFailure(KIND_CANCELED, "");
                        } else if (errorCode == BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT
                                || errorCode == BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT_PERMANENT) {
                            cb.onFailure(KIND_LOCKOUT, "E" + errorCode);
                        } else {
                            cb.onFailure(KIND_ERROR, "E" + errorCode);
                        }
                    }
                });
    }
}
