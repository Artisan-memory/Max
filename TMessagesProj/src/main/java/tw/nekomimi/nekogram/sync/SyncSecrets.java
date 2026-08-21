package tw.nekomimi.nekogram.sync;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.TextUtils;
import android.util.Base64;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Server credentials, kept apart from {@code NaConfig} on purpose: config values are written out
 * by the settings export, and passwords must not travel in an export file. Values are sealed with
 * a key held in the Android keystore, so lifting the preferences file off the device yields
 * ciphertext.
 */
public class SyncSecrets {
    public static final String KEY_ACCESS_PASSWORD = "sync_access_password";
    public static final String KEY_PASSPHRASE = "sync_passphrase";
    public static final String KEY_PROXY_TOKEN = "sync_proxy_token";

    private static final String PREFS = "maxsync_secrets";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "maxsync_secrets_key";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private SyncSecrets() {
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String get(String key) {
        String stored = prefs().getString(key, null);
        if (TextUtils.isEmpty(stored)) {
            return "";
        }
        try {
            byte[] blob = Base64.decode(stored, Base64.NO_WRAP);
            if (blob.length <= IV_LENGTH) {
                return "";
            }
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), new GCMParameterSpec(TAG_LENGTH_BITS, blob, 0, IV_LENGTH));
            byte[] plain = cipher.doFinal(blob, IV_LENGTH, blob.length - IV_LENGTH);
            return new String(plain, "UTF-8");
        } catch (Exception e) {
            FileLog.e("maxsync: cannot read stored secret", e);
            return "";
        }
    }

    public static void set(String key, String value) {
        if (TextUtils.isEmpty(value)) {
            prefs().edit().remove(key).apply();
            return;
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey());
            byte[] iv = cipher.getIV();
            byte[] sealed = cipher.doFinal(value.getBytes("UTF-8"));
            byte[] blob = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, blob, 0, iv.length);
            System.arraycopy(sealed, 0, blob, iv.length, sealed.length);
            prefs().edit().putString(key, Base64.encodeToString(blob, Base64.NO_WRAP)).apply();
        } catch (Exception e) {
            FileLog.e("maxsync: cannot store secret", e);
        }
    }

    public static boolean isSet(String key) {
        return !TextUtils.isEmpty(prefs().getString(key, null));
    }

    /** Wipes the credentials without touching anything already synced to the server. */
    public static void clearAll() {
        prefs().edit()
                .remove(KEY_ACCESS_PASSWORD)
                .remove(KEY_PASSPHRASE)
                .remove(KEY_PROXY_TOKEN)
                .apply();
    }

    private static SecretKey secretKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        KeyStore.Entry entry = keyStore.getEntry(KEY_ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }
}
