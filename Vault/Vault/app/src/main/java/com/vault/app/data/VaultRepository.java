package com.vault.app.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.vault.app.crypto.CryptoManager;
import com.vault.app.tools.PasswordTools;

import java.util.Collections;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import com.vault.app.tools.WebsiteFinder;

/** The only class the screens talk to. Encrypts on save, decrypts on request, never blocks the UI. */
public final class VaultRepository {

    /** Gets exactly one of: a value, or an error. Always called on the main thread. */
    public interface Callback<T> {
        void onDone(T value, Exception error);
    }

    public interface Progress {
        void onProgress(int done, int total);
    }

    private static VaultRepository instance;

    private final EntryDao dao;

    private final Context app;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private VaultRepository(Context ctx) {
        app = ctx;
        dao = AppDatabase.get(ctx).entryDao();
    }

    public static synchronized VaultRepository get(Context ctx) {
        if (instance == null) instance = new VaultRepository(ctx.getApplicationContext());
        return instance;
    }

    private <T> void run(Callable<T> job, Callback<T> cb) {
        io.execute(() -> {
            try {
                T result = job.call();
                main.post(() -> cb.onDone(result, null));
            } catch (Exception e) {
                main.post(() -> cb.onDone(null, e));
            }
        });
    }

    // ---------- Reading ----------

    /** All entries, still encrypted, with the username filled in as a display label. */
    public void list(Callback<List<Entry>> cb) {
        run(() -> {
            List<Entry> all = dao.getAll();
            for (Entry e : all) e.label = labelOf(e);
            // Group logins of the same site together, then order them by username
            Collections.sort(all, (a, b) -> {
                int byTitle = a.title.compareToIgnoreCase(b.title);
                if (byTitle != 0) return byTitle;
                return String.valueOf(a.label).compareToIgnoreCase(String.valueOf(b.label));
            });
            return all;
        }, cb);
    }

    /** The username for display. Needs no fingerprint. Null if the login has no label yet. */
    private static String labelOf(Entry e) {
        try {
            if (e.usernameHintEnc != null) {
                String user = CryptoManager.decryptLabel(e.usernameHintEnc);
                if (!user.isEmpty()) return user;
            }
        } catch (Exception ignored) {
            // no label yet
        }
        return null;
    }

    /** One entry by id, still encrypted. */
    public void getById(String id, Callback<Entry> cb) {
        run(() -> dao.getById(id), cb);
    }

    /** Decrypts one entry. Returns {username, password}. */
    public void reveal(Entry e, Callback<String[]> cb) {
        run(() -> new String[]{
                CryptoManager.decrypt(e.usernameEnc, e.id + ":user"),
                CryptoManager.decrypt(e.passwordEnc, e.id + ":pass")
        }, cb);
    }

    /** Blocking. Only for the autofill service, which already runs on its own thread. */
    public List<Entry> allSync() {
        return dao.getAll();
    }

    // ---------- Writing ----------

    /** Pass existingId = null to create a new login, or an id to overwrite one. */
    public void save(String existingId, String title, String domain, String packageName,
                     String username, String password, int score, int breachCount,
                     Callback<Entry> cb) {
        run(() -> {
            Entry e = new Entry();
            e.id = existingId != null ? existingId : UUID.randomUUID().toString();
            e.title = title;
            e.domain = domain;
            e.packageName = packageName;
            Entry old = existingId != null ? dao.getById(existingId) : null;
            e.extraDomains = old != null ? old.extraDomains : null;
            e.totpEnc = old != null ? old.totpEnc : null;
            e.usernameEnc = CryptoManager.encrypt(username, e.id + ":user");
            e.passwordEnc = CryptoManager.encrypt(password, e.id + ":pass");
            e.usernameHintEnc = CryptoManager.encryptLabel(username);
            e.score = score;
            e.breachCount = breachCount;
            e.updatedAt = System.currentTimeMillis();
            dao.upsert(e);
            return e;
        }, cb);
    }

    public void delete(Entry e, Callback<Boolean> cb) {
        run(() -> {
            dao.delete(e);
            return true;
        }, cb);
    }

    /** Remembers which website or app a login belongs to, but only fills in empty values. */
    public void associate(String id, String domain, String pkg, Callback<Boolean> cb) {
        run(() -> {
            Entry e = dao.getById(id);
            if (e == null) return false;
            boolean changed = false;
            if (domain != null) {
                if (e.domain == null || e.domain.isEmpty()) {
                    e.domain = domain;
                    changed = true;
                }
            } else if (pkg != null && (e.packageName == null || e.packageName.isEmpty())) {
                e.packageName = pkg;
                changed = true;
            }
            if (changed) dao.upsert(e);
            return changed;
        }, cb);
    }

    /**
     * Older logins have no username label yet. This adds it. Call it right after an unlock
     * (it needs the fingerprint key to be fresh). Failures are ignored and it retries next time.
     */
    public void backfillHints() {
        io.execute(() -> {
            try {
                for (Entry e : dao.getAll()) {
                    if (e.usernameHintEnc != null) continue;
                    String user = CryptoManager.decrypt(e.usernameEnc, e.id + ":user");
                    dao.setHint(e.id, CryptoManager.encryptLabel(user));
                }
            } catch (Exception ignored) {
                // key not fresh right now; the next unlock will try again
            }
        });
    }

    // ---------- Security checks ----------

    /**
     * Re-rates every password and re-checks it against known breaches. Updates the cached score
     * and breach count of every login. Returns the ids of logins that share a password with
     * another login. Call AppLock.requireFresh() before this.
     */
    public void recheckAll(Progress progress, Callback<Set<String>> cb) {
        run(() -> {
            List<Entry> all = dao.getAll();
            String[] pws = new String[all.size()];
            int[] scores = new int[all.size()];
            Map<String, List<String>> groups = new HashMap<>();

            // Step 1 (fast): decrypt and rate everything while the Keystore key is surely valid
            for (int i = 0; i < all.size(); i++) {
                Entry e = all.get(i);
                pws[i] = CryptoManager.decrypt(e.passwordEnc, e.id + ":pass");
                scores[i] = PasswordTools.analyze(pws[i]).score;
                groups.computeIfAbsent(sha256(pws[i]), k -> new ArrayList<>()).add(e.id);
            }

            // Step 2 (slow): breach check over the network, one at a time
            for (int i = 0; i < all.size(); i++) {
                int breach = all.get(i).breachCount; // keep the old value if we are offline
                try {
                    breach = PasswordTools.pwnedCount(pws[i]);
                } catch (IOException ignored) {
                }
                dao.updateHealth(all.get(i).id, scores[i], breach);
                pws[i] = null;
                int done = i + 1;
                int total = all.size();
                main.post(() -> progress.onProgress(done, total));
            }

            Set<String> reused = new HashSet<>();
            for (List<String> ids : groups.values()) {
                if (ids.size() > 1) reused.addAll(ids);
            }
            return reused;
        }, cb);
    }

    /** Quick local check: which logins share a password. No network. Call AppLock.requireFresh() first. */
    public void findReused(Callback<Set<String>> cb) {
        run(() -> {
            Map<String, List<String>> groups = new HashMap<>();
            for (Entry e : dao.getAll()) {
                String pw = CryptoManager.decrypt(e.passwordEnc, e.id + ":pass");
                groups.computeIfAbsent(sha256(pw), k -> new ArrayList<>()).add(e.id);
            }
            Set<String> reused = new HashSet<>();
            for (List<String> ids : groups.values()) {
                if (ids.size() > 1) reused.addAll(ids);
            }
            return reused;
        }, cb);
    }

    /** Passwords are compared by their hash, so the map never holds plaintext. */
    private static String sha256(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    // ---------- "Save to Vault?" prompt ----------

    /**
     * Updates the matching login if there is one for this site and username, otherwise creates a
     * new one. Returns "saved", "updated", "unchanged", or "needs_username" when it cannot tell
     * which login is meant. Needs a recently unlocked key.
     *
     * changeForm: the page was a change/reset-password page (no username box).
     * allowEmptyUsername: the user was asked and chose to leave the username empty.
     */
    public void upsertCredential(String title, String domain, String pkg,
                                 String username, String password,
                                 boolean changeForm, boolean allowEmptyUsername,
                                 Callback<String> cb) {
        run(() -> {
            String user = username == null ? "" : username;

            List<Entry> candidates = new ArrayList<>();
            for (Entry e : dao.getAll()) {
                boolean sameSite = domain != null
                        ? e.matchesSite(domain)
                        : (pkg != null && pkg.equals(e.packageName));
                if (sameSite) candidates.add(e);
            }

            // Same site and same username (empty equals empty) means the same login
            Entry match = null;
            for (Entry e : candidates) {
                if (CryptoManager.decrypt(e.usernameEnc, e.id + ":user").equalsIgnoreCase(user)) {
                    match = e;
                    break;
                }
            }

            if (match == null && user.isEmpty()) {
                if (changeForm && candidates.size() == 1) {
                    // A reset page often shows no username: the site's only login is the one
                    match = candidates.get(0);
                } else if (!allowEmptyUsername) {
                    return "needs_username";
                }
            }

            if (match != null) {
                String stored = CryptoManager.decrypt(match.passwordEnc, match.id + ":pass");
                if (stored.equals(password)) return "unchanged";
                match.passwordEnc = CryptoManager.encrypt(password, match.id + ":pass");
                if (match.usernameHintEnc == null) {
                    String storedUser = CryptoManager.decrypt(match.usernameEnc, match.id + ":user");
                    match.usernameHintEnc = CryptoManager.encryptLabel(storedUser);
                }
                match.score = PasswordTools.analyze(password).score;
                match.breachCount = -1;
                match.updatedAt = System.currentTimeMillis();
                dao.upsert(match);
                return "updated";
            }

            Entry fresh = new Entry();
            fresh.id = UUID.randomUUID().toString();
            fresh.title = title;
            fresh.domain = domain;
            fresh.packageName = domain == null ? pkg : null;
            fresh.usernameEnc = CryptoManager.encrypt(user, fresh.id + ":user");
            fresh.passwordEnc = CryptoManager.encrypt(password, fresh.id + ":pass");
            fresh.usernameHintEnc = CryptoManager.encryptLabel(user);
            fresh.score = PasswordTools.analyze(password).score;
            fresh.breachCount = -1;
            fresh.updatedAt = System.currentTimeMillis();
            dao.upsert(fresh);
            return "saved";
        }, cb);
    }
    /**
     * Finds the websites of app logins. Fills an empty website, upgrades a website that is only a
     * secondary address of the app (like instagr.am to instagram.com), and keeps the list of the
     * app's other websites up to date. Never replaces a website you typed yourself. No fingerprint
     * and no internet needed. onChanged runs on the main thread if anything changed.
     */
    public void enrichDomains(Runnable onChanged) {
        io.execute(() -> {
            boolean changed = false;
            try {
                for (Entry e : dao.getAll()) {
                    if (e.packageName == null || e.packageName.isEmpty()) continue;
                    WebsiteFinder.Result r = WebsiteFinder.find(app, e.packageName, false);
                    if (r == null || !r.trusted) continue;

                    String current = e.domain == null ? "" : e.domain;
                    List<String> known = new ArrayList<>();
                    known.add(r.domain);
                    known.addAll(r.aliases);

                    boolean replace = current.isEmpty()
                            || (!current.equals(r.domain) && known.contains(current));
                    String primary = replace ? r.domain : current;

                    StringBuilder extra = new StringBuilder();
                    for (String d : known) {
                        if (d.equals(primary)) continue;
                        if (extra.length() > 0) extra.append(',');
                        extra.append(d);
                    }
                    String extras = extra.length() == 0 ? null : extra.toString();

                    boolean same = primary.equals(current)
                            && (extras == null ? e.extraDomains == null : extras.equals(e.extraDomains));
                    if (same) continue;

                    dao.setSites(e.id, primary, extras);
                    changed = true;
                }
            } catch (Exception ignored) {
                // try again next time
            }
            if (changed && onChanged != null) main.post(onChanged);
        });
    }
    /** Decrypts the verification-code setup of a login. Returns null if it has none. */
    public void revealTotp(Entry e, Callback<String> cb) {
        run(() -> e.totpEnc == null ? null : CryptoManager.decrypt(e.totpEnc, e.id + ":totp"), cb);
    }

    /** Stores the verification-code setup, or removes it when config is null. Needs a fresh key. */
    public void saveTotp(String id, String config, Callback<Boolean> cb) {
        run(() -> {
            dao.setTotp(id, config == null ? null : CryptoManager.encrypt(config, id + ":totp"));
            return true;
        }, cb);
    }
}