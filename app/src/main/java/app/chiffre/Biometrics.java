package app.chiffre;

import android.app.Activity;
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

    static final int STATUS_UNSUPPORTED = 0;
    static final int STATUS_NOT_ENROLLED = 1;
    static final int STATUS_READY = 2;

    interface Callback {
        /** Beim Einrichten ist data leer, beim Entsperren das Passwort. */
        void onSuccess(byte[] data);

        void onFailure(int kind);
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

    private static SecretKey createKey() throws GeneralSecurityException {
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
        if (Build.VERSION.SDK_INT >= 28) {
            b.setUnlockedDeviceRequired(true);
        }
        kg.init(b.build());
        return kg.generateKey();
    }

    private static SecretKey loadKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        return (SecretKey) ks.getKey(KEY_ALIAS, null);
    }

    /** Richtet den Fingerabdruck ein: Schlüssel anlegen, Fingerabdruck abfragen, Passwort verschlüsseln. */
    static void enable(Activity a, byte[] secret, Callback cb) {
        if (Build.VERSION.SDK_INT < 28) {
            cb.onFailure(KIND_ERROR);
            return;
        }
        final Cipher cipher;
        try {
            clear(a);
            SecretKey key = createKey();
            cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key);
        } catch (Exception e) {
            clear(a);
            cb.onFailure(KIND_ERROR);
            return;
        }
        prompt(a, a.getString(R.string.bio_title_enable), cipher, new Callback() {
            @Override
            public void onSuccess(byte[] unused) {
                try {
                    byte[] ct = cipher.doFinal(secret);
                    prefs(a).edit()
                            .putString(K_BLOB, Base64.encodeToString(ct, Base64.NO_WRAP))
                            .putString(K_IV, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                            .apply();
                    cb.onSuccess(new byte[0]);
                } catch (Exception e) {
                    clear(a);
                    cb.onFailure(KIND_ERROR);
                } finally {
                    Arrays.fill(secret, (byte) 0);
                }
            }

            @Override
            public void onFailure(int kind) {
                clear(a);
                Arrays.fill(secret, (byte) 0);
                cb.onFailure(kind);
            }
        });
    }

    /** Fragt den Fingerabdruck ab und liefert bei Erfolg das gemerkte Passwort. */
    static void unlock(Activity a, Callback cb) {
        if (Build.VERSION.SDK_INT < 28 || !isEnabled(a)) {
            cb.onFailure(KIND_ERROR);
            return;
        }
        final Cipher cipher;
        final byte[] blob;
        try {
            SecretKey key = loadKey();
            if (key == null) {
                clear(a);
                cb.onFailure(KIND_INVALID);
                return;
            }
            byte[] iv = Base64.decode(prefs(a).getString(K_IV, ""), Base64.NO_WRAP);
            blob = Base64.decode(prefs(a).getString(K_BLOB, ""), Base64.NO_WRAP);
            cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
        } catch (KeyPermanentlyInvalidatedException e) {
            clear(a);
            cb.onFailure(KIND_INVALID);
            return;
        } catch (Exception e) {
            clear(a);
            cb.onFailure(KIND_INVALID);
            return;
        }
        prompt(a, a.getString(R.string.bio_title_unlock), cipher, new Callback() {
            @Override
            public void onSuccess(byte[] unused) {
                try {
                    cb.onSuccess(cipher.doFinal(blob));
                } catch (Exception e) {
                    clear(a);
                    cb.onFailure(KIND_INVALID);
                }
            }

            @Override
            public void onFailure(int kind) {
                cb.onFailure(kind);
            }
        });
    }

    private static void prompt(Activity a, String title, Cipher cipher, Callback cb) {
        if (Build.VERSION.SDK_INT < 28) {
            cb.onFailure(KIND_ERROR);
            return;
        }
        BiometricPrompt.Builder builder = new BiometricPrompt.Builder(a)
                .setTitle(title)
                .setNegativeButton(a.getString(R.string.bio_cancel), a.getMainExecutor(),
                        (dialog, which) -> cb.onFailure(KIND_CANCELED));
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
                        boolean canceled = errorCode == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED
                                || errorCode == BiometricPrompt.BIOMETRIC_ERROR_CANCELED;
                        cb.onFailure(canceled ? KIND_CANCELED : KIND_ERROR);
                    }
                });
    }
}
