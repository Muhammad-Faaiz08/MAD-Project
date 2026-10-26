package com.vault.app.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "entries", indices = {@Index("domain")})
public class Entry {
    @PrimaryKey
    @NonNull
    public String id = "";

    public String title;
    public String domain;          // e.g. github.com (autofill matches websites with this)
    public String extraDomains;    // other websites of the same service, comma separated
    public String packageName;     // e.g. com.instagram.android (autofill matches apps with this), can be null

    public byte[] usernameEnc;     // encrypted with the fingerprint key
    public byte[] passwordEnc;     // encrypted with the fingerprint key
    public byte[] usernameHintEnc; // username locked with a key that needs no fingerprint (labels only)
    public byte[] totpEnc;         // verification-code setup, encrypted with the fingerprint key

    public int score;              // zxcvbn 0..4, cached so the list needs no decryption
    public int breachCount = -1;   // -1 means never checked
    public long updatedAt;

    @Ignore
    public String label;           // the username, filled in only for display (not stored in the database)

    /** true if this web page (e.g. "accounts.instagram.com") belongs to this login. */
    public boolean matchesSite(String page) {
        if (page == null) return false;
        if (sameSite(domain, page)) return true;
        if (extraDomains != null) {
            for (String d : extraDomains.split(",")) {
                if (sameSite(d.trim(), page)) return true;
            }
        }
        return false;
    }

    private static boolean sameSite(String stored, String page) {
        return stored != null && !stored.isEmpty()
                && (page.equals(stored) || page.endsWith("." + stored));
    }
}