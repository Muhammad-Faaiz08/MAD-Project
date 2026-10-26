package com.vault.app.crypto;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Encrypts and decrypts text with AES-256 keys that never leave the Android Keystore. */
public final class CryptoManager {
    private static final String ALIAS = "vault_master_key";   // needs fingerprint/PIN
    private static final String LABEL_ALIAS = "vault_label_key"; // no fingerprint, labels only
    private static final String PROVIDER = "AndroidKeyStore";
    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;
    /** After a successful unlock, the main key stays usable for this many seconds. */
    public static final int AUTH_SECONDS = 300;

    private CryptoManager() {}

    // ---------- Keys ----------

    private static KeyStore keyStore() throws GeneralSecurityException, IOException {
        KeyStore ks = KeyStore.getInstance(PROVIDER);
        ks.load(null);
        return ks;
    }

    private static SecretKey getKey() throws GeneralSecurityException, IOException {
        Key existing = keyStore().getKey(ALIAS, null);
        if (existing != null) return (SecretKey) existing;

        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER);
        kg.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationParameters(AUTH_SECONDS,
                        KeyProperties.AUTH_BIOMETRIC_STRONG | KeyProperties.AUTH_DEVICE_CREDENTIAL)
                .build());
        return kg.generateKey();
    }

    private static SecretKey getLabelKey() throws GeneralSecurityException, IOException {
        Key existing = keyStore().getKey(LABEL_ALIAS, null);
        if (existing != null) return (SecretKey) existing;

        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER);
        kg.init(new KeyGenParameterSpec.Builder(LABEL_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }

    // ---------- Secrets (need a recent fingerprint/PIN) ----------

    /** Returns [12-byte IV][ciphertext + tag]. `aad` ties the data to one row. */
    public static byte[] encrypt(String plain, String aad) throws GeneralSecurityException, IOException {
        return seal(getKey(), plain, aad);
    }

    public static String decrypt(byte[] blob, String aad) throws GeneralSecurityException, IOException {
        return open(getKey(), blob, aad);
    }

    // ---------- Labels (no fingerprint needed; only for showing a username in suggestions) ----------

    public static byte[] encryptLabel(String plain) throws GeneralSecurityException, IOException {
        return seal(getLabelKey(), plain, "label");
    }

    public static String decryptLabel(byte[] blob) throws GeneralSecurityException, IOException {
        return open(getLabelKey(), blob, "label");
    }

    // ---------- Shared work ----------

    private static byte[] seal(SecretKey key, String plain, String aad) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance(TRANSFORM);
        c.init(Cipher.ENCRYPT_MODE, key);
        c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        byte[] iv = c.getIV();
        byte[] out = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(ct, 0, out, iv.length, ct.length);
        return out;
    }

    private static String open(SecretKey key, byte[] blob, String aad) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance(TRANSFORM);
        c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, blob, 0, IV_LEN));
        c.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        byte[] pt = c.doFinal(blob, IV_LEN, blob.length - IV_LEN);
        return new String(pt, StandardCharsets.UTF_8);
    }
}