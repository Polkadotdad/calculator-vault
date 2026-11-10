package com.dhillon.calculatorvault;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaDataSource;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * File encryption. A random AES-256 data key encrypts files with AES/CTR (streamable and
 * seekable, so large videos play without writing plain copies to disk). The data key itself
 * is wrapped by a hardware-backed key in the Android Keystore.
 */
final class VaultCrypto {

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String MASTER_ALIAS = "cv_master_key";
    private static final String CTR = "AES/CTR/NoPadding";
    static final int IV_LEN = 16;

    private static SecretKey dataKey;

    private VaultCrypto() {
    }

    static synchronized SecretKey dataKey(Context c) throws Exception {
        if (dataKey != null) return dataKey;
        SharedPreferences p = Security.prefs(c);
        String wrapped = p.getString("dk_wrapped", null);
        String iv = p.getString("dk_iv", null);
        SecretKey master = masterKey();
        Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
        if (wrapped == null || iv == null) {
            byte[] raw = Security.random(32);
            gcm.init(Cipher.ENCRYPT_MODE, master);
            byte[] enc = gcm.doFinal(raw);
            p.edit().putString("dk_wrapped", Security.b64(enc))
                    .putString("dk_iv", Security.b64(gcm.getIV()))
                    .commit();
            dataKey = new SecretKeySpec(raw, "AES");
        } else {
            gcm.init(Cipher.DECRYPT_MODE, master, new GCMParameterSpec(128, Security.unb64(iv)));
            dataKey = new SecretKeySpec(gcm.doFinal(Security.unb64(wrapped)), "AES");
        }
        return dataKey;
    }

    private static SecretKey masterKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        if (ks.containsAlias(MASTER_ALIAS)) {
            return (SecretKey) ks.getKey(MASTER_ALIAS, null);
        }
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        kg.init(new KeyGenParameterSpec.Builder(MASTER_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return kg.generateKey();
    }

    /** Encrypts everything from {@code in} into {@code out}. Returns the number of plain bytes. */
    static long encrypt(Context c, InputStream in, File out) throws Exception {
        byte[] iv = Security.random(IV_LEN);
        Cipher cipher = Cipher.getInstance(CTR);
        cipher.init(Cipher.ENCRYPT_MODE, dataKey(c), new IvParameterSpec(iv));
        long total = 0;
        try (FileOutputStream fos = new FileOutputStream(out)) {
            fos.write(iv);
            try (OutputStream cos = new CipherOutputStream(fos, cipher)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    cos.write(buf, 0, n);
                    total += n;
                }
            }
        }
        return total;
    }

    static InputStream decrypt(Context c, File f) throws Exception {
        FileInputStream fis = new FileInputStream(f);
        byte[] iv = new byte[IV_LEN];
        int read = 0;
        while (read < IV_LEN) {
            int n = fis.read(iv, read, IV_LEN - read);
            if (n < 0) {
                fis.close();
                throw new IOException("File is damaged");
            }
            read += n;
        }
        Cipher cipher = Cipher.getInstance(CTR);
        cipher.init(Cipher.DECRYPT_MODE, dataKey(c), new IvParameterSpec(iv));
        return new CipherInputStream(fis, cipher);
    }

    /** Adds a block count to a 128-bit big-endian counter. */
    static byte[] ivPlus(byte[] iv, long blocks) {
        byte[] r = iv.clone();
        long carry = blocks;
        for (int i = r.length - 1; i >= 0 && carry != 0; i--) {
            long sum = (r[i] & 0xFFL) + (carry & 0xFFL);
            r[i] = (byte) sum;
            carry = (carry >>> 8) + (sum >>> 8);
        }
        return r;
    }

    /** Random-access decrypting source for MediaPlayer / MediaMetadataRetriever. */
    static final class EncryptedDataSource extends MediaDataSource {
        private final RandomAccessFile raf;
        private final SecretKey key;
        private final byte[] iv = new byte[IV_LEN];
        private final long length;
        private final Cipher cipher;
        private byte[] tmp = new byte[64 * 1024];

        EncryptedDataSource(Context c, File f) throws Exception {
            raf = new RandomAccessFile(f, "r");
            raf.readFully(iv);
            length = raf.length() - IV_LEN;
            key = dataKey(c);
            cipher = Cipher.getInstance(CTR);
        }

        @Override
        public synchronized int readAt(long position, byte[] buffer, int offset, int size) throws IOException {
            if (position >= length) return -1;
            if (size <= 0) return 0;
            int n = (int) Math.min(size, length - position);
            if (tmp.length < n) tmp = new byte[n];
            raf.seek(IV_LEN + position);
            raf.readFully(tmp, 0, n);
            try {
                cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(ivPlus(iv, position / 16)));
                int skip = (int) (position % 16);
                if (skip > 0) cipher.update(new byte[skip]);
                return cipher.doFinal(tmp, 0, n, buffer, offset);
            } catch (Exception e) {
                throw new IOException(e);
            }
        }

        @Override
        public long getSize() {
            return length;
        }

        @Override
        public synchronized void close() throws IOException {
            raf.close();
        }
    }
}
