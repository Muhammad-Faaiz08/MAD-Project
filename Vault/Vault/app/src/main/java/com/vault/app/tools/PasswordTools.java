package com.vault.app.tools;

import android.os.Handler;
import android.os.Looper;

import com.nulabinc.zxcvbn.Strength;
import com.nulabinc.zxcvbn.Zxcvbn;
import com.vault.app.data.VaultRepository;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Generate passwords, rate their strength, and check them against known breaches. */
public final class PasswordTools {

    /** Result of a strength check. score is 0 (very weak) to 4 (strong). */
    public static final class Rating {
        public final int score;
        public final String label;
        public final String hint; // advice like "Add another word", may be empty

        Rating(int score, String label, String hint) {
            this.score = score;
            this.label = label;
            this.hint = hint;
        }
    }

    private static final String[] LABELS = {"Very weak", "Weak", "Fair", "Good", "Strong"};
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ExecutorService CPU = Executors.newSingleThreadExecutor();
    private static final ExecutorService NET = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Zxcvbn zxcvbn;

    private PasswordTools() {}

    // ---------- Generator ----------

    /** Random password with at least one lowercase, uppercase, digit and symbol. No look-alike characters. */
    public static String generate(int length) {
        int n = Math.max(8, Math.min(length, 64));
        String[] sets = {
                "abcdefghijkmnopqrstuvwxyz",
                "ABCDEFGHJKLMNPQRSTUVWXYZ",
                "23456789",
                "!@#$%^&*-_=+?"
        };
        StringBuilder all = new StringBuilder();
        for (String s : sets) all.append(s);

        char[] out = new char[n];
        for (int i = 0; i < sets.length; i++) {
            out[i] = sets[i].charAt(RANDOM.nextInt(sets[i].length()));
        }
        for (int i = sets.length; i < n; i++) {
            out[i] = all.charAt(RANDOM.nextInt(all.length()));
        }
        // shuffle so the guaranteed characters aren't always first
        for (int i = n - 1; i > 0; i--) {
            int j = RANDOM.nextInt(i + 1);
            char t = out[i];
            out[i] = out[j];
            out[j] = t;
        }
        return new String(out);
    }

    // ---------- Strength ----------

    /** Blocking. Call from a background thread, or use analyzeAsync. */
    public static synchronized Rating analyze(String password) {
        if (zxcvbn == null) zxcvbn = new Zxcvbn();
        if (password.isEmpty()) return new Rating(0, LABELS[0], "");

        Strength s = zxcvbn.measure(password.length() > 100 ? password.substring(0, 100) : password);
        int score = s.getScore();
        String hint = s.getFeedback().getWarning();
        if (hint == null || hint.isEmpty()) {
            List<String> tips = s.getFeedback().getSuggestions();
            hint = (tips == null || tips.isEmpty()) ? "" : tips.get(0);
        }
        return new Rating(score, LABELS[score], hint);
    }

    // ---------- Breach check (Have I Been Pwned, k-anonymity) ----------

    /** Blocking. Returns how many times the password appears in known breaches (0 = not found). */
    public static int pwnedCount(String password) throws IOException {
        String hash = sha1Hex(password);
        String prefix = hash.substring(0, 5);   // only this part leaves the phone
        String suffix = hash.substring(5);

        HttpURLConnection c = (HttpURLConnection)
                new URL("https://api.pwnedpasswords.com/range/" + prefix).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Add-Padding", "true"); // hides the real response size
        c.setRequestProperty("User-Agent", "VaultApp");
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.regionMatches(true, 0, suffix, 0, suffix.length())) {
                    int colon = line.indexOf(':');
                    return colon < 0 ? 0 : Integer.parseInt(line.substring(colon + 1).trim());
                }
            }
            return 0;
        } finally {
            c.disconnect();
        }
    }

    private static String sha1Hex(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02X", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------- Async wrappers (safe to call from screens) ----------

    public static void analyzeAsync(String password, VaultRepository.Callback<Rating> cb) {
        run(CPU, () -> analyze(password), cb);
    }

    public static void pwnedAsync(String password, VaultRepository.Callback<Integer> cb) {
        run(NET, () -> pwnedCount(password), cb);
    }

    private static <T> void run(ExecutorService pool, Callable<T> job, VaultRepository.Callback<T> cb) {
        pool.execute(() -> {
            try {
                T result = job.call();
                MAIN.post(() -> cb.onDone(result, null));
            } catch (Exception e) {
                MAIN.post(() -> cb.onDone(null, e));
            }
        });
    }
}
