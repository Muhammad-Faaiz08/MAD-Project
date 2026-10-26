package com.vault.app.tools;


import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Makes the codes that authenticator apps show (TOTP, RFC 6238). */
public final class TotpGenerator {

    public static final class Config {
        public final String secret;     // base32, upper case, no spaces
        public final int digits;
        public final int period;        // seconds a code stays valid
        public final String algorithm;  // SHA1, SHA256 or SHA512

        Config(String secret, int digits, int period, String algorithm) {
            this.secret = secret;
            this.digits = digits;
            this.period = period;
            this.algorithm = algorithm;
        }

        /** Compact text form that gets encrypted and stored. */
        public String serialize() {
            return secret + ";" + digits + ";" + period + ";" + algorithm;
        }
    }

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private TotpGenerator() {}

    public static Config fromStored(String stored) {
        String[] p = stored.split(";");
        return new Config(p[0], Integer.parseInt(p[1]), Integer.parseInt(p[2]), p[3]);
    }

    /** Accepts a plain setup key ("JBSW Y3DP EHPK 3PXP") or an otpauth://totp/... link. Null if invalid. */
    public static Config parse(String input) {
        if (input == null) return null;
        String text = input.trim();
        int digits = 6;
        int period = 30;
        String algorithm = "SHA1";
        String secret;
        try {
            if (text.toLowerCase(Locale.ROOT).startsWith("otpauth://")) {
                Uri uri = Uri.parse(text);
                if (!"totp".equalsIgnoreCase(uri.getHost())) return null;
                secret = uri.getQueryParameter("secret");
                String d = uri.getQueryParameter("digits");
                if (d != null) digits = Integer.parseInt(d);
                String p = uri.getQueryParameter("period");
                if (p != null) period = Integer.parseInt(p);
                String a = uri.getQueryParameter("algorithm");
                if (a != null) algorithm = a.toUpperCase(Locale.ROOT);
            } else {
                secret = text;
            }
            if (secret == null) return null;
            secret = secret.replaceAll("[\\s-]", "").replace("=", "").toUpperCase(Locale.ROOT);
            if (secret.length() < 8 || !secret.matches("[A-Z2-7]+")) return null;
            if (digits < 6 || digits > 8 || period < 10 || period > 120) return null;
            if (!algorithm.equals("SHA1") && !algorithm.equals("SHA256") && !algorithm.equals("SHA512")) {
                return null;
            }
            return new Config(secret, digits, period, algorithm);
        } catch (Exception e) {
            return null;
        }
    }

    /** The code for a given moment, e.g. "482913". */
    public static String code(Config c, long nowMillis) {
        try {
            long counter = nowMillis / 1000 / c.period;
            byte[] message = ByteBuffer.allocate(8).putLong(counter).array();
            String macName = "Hmac" + c.algorithm;
            Mac mac = Mac.getInstance(macName);
            mac.init(new SecretKeySpec(base32(c.secret), macName));
            byte[] h = mac.doFinal(message);

            int offset = h[h.length - 1] & 0x0f;
            int binary = ((h[offset] & 0x7f) << 24)
                    | ((h[offset + 1] & 0xff) << 16)
                    | ((h[offset + 2] & 0xff) << 8)
                    | (h[offset + 3] & 0xff);
            int modulus = 1;
            for (int i = 0; i < c.digits; i++) modulus *= 10;
            return String.format(Locale.US, "%0" + c.digits + "d", binary % modulus);
        } catch (Exception e) {
            return "------";
        }
    }

    /** Seconds until the current code is replaced. */
    public static int secondsLeft(Config c, long nowMillis) {
        return c.period - (int) ((nowMillis / 1000) % c.period);
    }

    private static byte[] base32(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char ch : s.toCharArray()) {
            int value = ALPHABET.indexOf(ch);
            if (value < 0) continue;
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }
}
