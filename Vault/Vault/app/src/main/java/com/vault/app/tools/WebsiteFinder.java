package com.vault.app.tools;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.verify.domain.DomainVerificationManager;
import android.content.pm.verify.domain.DomainVerificationUserState;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.RequiresApi;

import com.vault.app.data.VaultRepository;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Works out which website an installed Android app belongs to. */
public final class WebsiteFinder {

    public static final class Result {
        public final String domain;          // the main website
        public final boolean trusted;        // true = declared by the app and verified by Android
        public final List<String> aliases;   // other verified websites of the same app

        Result(String domain, boolean trusted, List<String> aliases) {
            this.domain = domain;
            this.trusted = trusted;
            this.aliases = aliases;
        }
    }

    private static final int MAX_ALIASES = 8;
    private static final Set<String> GENERIC = new HashSet<>(Arrays.asList(
            "com", "org", "net", "android", "app", "apps", "mobile", "www", "main",
            "client", "free", "lite"));
    private static final ExecutorService NET = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private WebsiteFinder() {}

    /** Looks for a website including guesses (uses the internet). Result may be null. */
    public static void suggest(Context ctx, String pkg, VaultRepository.Callback<Result> cb) {
        Context app = ctx.getApplicationContext();
        NET.execute(() -> {
            Result r = find(app, pkg, true);
            MAIN.post(() -> cb.onDone(r, null));
        });
    }

    /** Blocking. With allowGuesses = false it never touches the internet. */
    public static Result find(Context ctx, String pkg, boolean allowGuesses) {
        String label = labelOf(ctx, pkg);
        List<String> names = nameWords(pkg, label);

        Result declared = Build.VERSION.SDK_INT >= 31 ? declared(ctx, pkg, names) : null;
        if (declared != null) return declared;

        if (allowGuesses) {
            for (String guess : guesses(pkg, label)) {
                if (exists(guess)) return new Result(guess, false, Collections.<String>emptyList());
            }
        }
        return null;
    }

    // ---------- 1. What the app declares ----------

    @RequiresApi(31)
    private static Result declared(Context ctx, String pkg, List<String> names) {
        try {
            DomainVerificationManager dvm = ctx.getSystemService(DomainVerificationManager.class);
            if (dvm == null) return null;
            DomainVerificationUserState state = dvm.getDomainVerificationUserState(pkg);
            if (state == null) return null;

            List<String> verified = new ArrayList<>();
            String best = null;
            for (Map.Entry<String, Integer> h : state.getHostToStateMap().entrySet()) {
                if (h.getValue() == DomainVerificationUserState.DOMAIN_STATE_NONE) continue; // not verified
                String base = baseDomain(h.getKey());
                if (base == null) continue;
                if (!verified.contains(base)) verified.add(base);
                if (nameMatches(base, names) && (best == null || isBetter(base, best))) best = base;
            }
            if (best == null) return null;

            List<String> aliases = new ArrayList<>();
            for (String d : verified) {
                if (!d.equals(best) && aliases.size() < MAX_ALIASES) aliases.add(d);
            }
            return new Result(best, true, aliases);
        } catch (Exception e) {
            return null;
        }
    }

    /** The main website's name must be exactly one of the app's own words, e.g. "instagram". */
    private static boolean nameMatches(String base, List<String> names) {
        String name = base.substring(0, base.indexOf('.'));
        return names.contains(name);
    }

    /** Prefer ".com", otherwise the shorter one. */
    private static boolean isBetter(String candidate, String current) {
        boolean candidateCom = candidate.endsWith(".com");
        boolean currentCom = current.endsWith(".com");
        if (candidateCom != currentCom) return candidateCom;
        return candidate.length() < current.length();
    }

    /** "l.instagram.com" becomes "instagram.com", "news.bbc.co.uk" becomes "bbc.co.uk". */
    private static String baseDomain(String host) {
        String h = host.toLowerCase(Locale.ROOT).trim();
        if (h.startsWith("*.")) h = h.substring(2);
        if (!h.matches("[a-z0-9.-]+") || !h.contains(".")) return null;
        String[] p = h.split("\\.");
        int n = p.length;
        if (n < 2) return null;
        boolean twoPartSuffix = n >= 3 && p[n - 1].length() == 2
                && Arrays.asList("co", "com", "org", "net", "ac", "gov").contains(p[n - 2]);
        return twoPartSuffix
                ? p[n - 3] + "." + p[n - 2] + "." + p[n - 1]
                : p[n - 2] + "." + p[n - 1];
    }

    // ---------- 2. Guesses ----------

    private static List<String> guesses(String pkg, String label) {
        List<String> out = new ArrayList<>();
        String[] parts = pkg.split("\\.");
        if (parts.length >= 2) {
            String tld = parts[0].matches("org|net|io|app|dev|ai|me|tv") ? parts[0] : "com";
            out.add(parts[1].toLowerCase(Locale.ROOT) + "." + tld);
        }
        String key = alnum(label);
        if (key.length() >= 3) out.add(key + ".com");
        return out;
    }

    /** Any answer from the server (even "forbidden") means the website exists. */
    private static boolean exists(String host) {
        if (!host.matches("[a-z0-9.-]+")) return false;
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("https://" + host + "/").openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.setRequestProperty("User-Agent", "VaultApp");
            try {
                return c.getResponseCode() < 500;
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            return false;
        }
    }

    // ---------- Helpers ----------

    private static List<String> nameWords(String pkg, String label) {
        List<String> words = new ArrayList<>();
        for (String p : pkg.split("\\.")) {
            p = p.toLowerCase(Locale.ROOT);
            if (p.length() >= 3 && !GENERIC.contains(p)) words.add(p);
        }
        String key = alnum(label);
        if (key.length() >= 3) words.add(key);
        return words;
    }

    private static String labelOf(Context ctx, String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String alnum(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}