package com.dhillon.calculatorvault;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/** Stores the secret code and recovery answer as salted PBKDF2 hashes. */
final class Security {

    static final String[] QUESTIONS = {
            "What was the name of your first school?",
            "What is the name of your home village or town?",
            "What was your first vehicle?",
            "What is your favourite food?",
            "What is your best friend's first name?"
    };

    private static final String PREFS = "cv_prefs";
    private static final int ITERATIONS = 20000;
    private static final long LOCKOUT_MS = 30_000L;
    private static final int MAX_FAILS = 5;

    private Security() {
    }

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean hasCode(Context c) {
        return prefs(c).contains("code_hash");
    }

    static boolean isValidCodeFormat(String s) {
        return s != null && s.matches("\\d{4,8}");
    }

    static void setCode(Context c, String code) {
        byte[] salt = random(16);
        prefs(c).edit()
                .putString("code_salt", b64(salt))
                .putString("code_hash", b64(hash(code, salt)))
                .commit();
    }

    static boolean checkCode(Context c, String code) {
        SharedPreferences p = prefs(c);
        String salt = p.getString("code_salt", null);
        String h = p.getString("code_hash", null);
        if (salt == null || h == null) return false;
        return MessageDigest.isEqual(unb64(h), hash(code, unb64(salt)));
    }

    static void setRecovery(Context c, int questionIndex, String answer) {
        byte[] salt = random(16);
        prefs(c).edit()
                .putInt("rec_q", questionIndex)
                .putString("rec_salt", b64(salt))
                .putString("rec_hash", b64(hash(normalize(answer), salt)))
                .commit();
    }

    static boolean hasRecovery(Context c) {
        return prefs(c).contains("rec_hash");
    }

    static String recoveryQuestion(Context c) {
        int i = prefs(c).getInt("rec_q", 0);
        if (i < 0 || i >= QUESTIONS.length) i = 0;
        return QUESTIONS[i];
    }

    /** Milliseconds the user must still wait before another recovery attempt (0 = allowed). */
    static long recoveryWaitMs(Context c) {
        long until = prefs(c).getLong("rec_lock_until", 0L);
        return Math.max(0L, until - System.currentTimeMillis());
    }

    static boolean checkRecovery(Context c, String answer) {
        SharedPreferences p = prefs(c);
        String salt = p.getString("rec_salt", null);
        String h = p.getString("rec_hash", null);
        if (salt == null || h == null) return false;
        boolean ok = MessageDigest.isEqual(unb64(h), hash(normalize(answer), unb64(salt)));
        if (ok) {
            p.edit().putInt("rec_fails", 0).putLong("rec_lock_until", 0L).apply();
        } else {
            int fails = p.getInt("rec_fails", 0) + 1;
            SharedPreferences.Editor e = p.edit().putInt("rec_fails", fails);
            if (fails >= MAX_FAILS) {
                e.putInt("rec_fails", 0).putLong("rec_lock_until", System.currentTimeMillis() + LOCKOUT_MS);
            }
            e.apply();
        }
        return ok;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static byte[] hash(String secret, byte[] salt) {
        try {
            PBEKeySpec spec = new PBEKeySpec(secret.toCharArray(), salt, ITERATIONS, 256);
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return f.generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] random(int n) {
        byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);
        return b;
    }

    static String b64(byte[] b) {
        return Base64.encodeToString(b, Base64.NO_WRAP);
    }

    static byte[] unb64(String s) {
        return Base64.decode(s, Base64.NO_WRAP);
    }
}
