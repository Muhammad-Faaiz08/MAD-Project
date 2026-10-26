package com.vault.app.data;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface EntryDao {
    @Query("SELECT * FROM entries ORDER BY title COLLATE NOCASE ASC")
    List<Entry> getAll();

    @Query("SELECT * FROM entries WHERE id = :id LIMIT 1")
    Entry getById(String id);

    @Query("SELECT * FROM entries WHERE domain = :domain OR packageName = :pkg")
    List<Entry> findForAutofill(String domain, String pkg);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsert(Entry entry);

    @Delete
    void delete(Entry entry);

    @Query("UPDATE entries SET score = :score, breachCount = :breach WHERE id = :id")
    void updateHealth(String id, int score, int breach);

    @Query("UPDATE entries SET usernameHintEnc = :hint WHERE id = :id")
    void setHint(String id, byte[] hint);

    @Query("UPDATE entries SET domain = :domain, extraDomains = :extra WHERE id = :id")
    void setSites(String id, String domain, String extra);

    @Query("UPDATE entries SET totpEnc = :blob WHERE id = :id")
    void setTotp(String id, byte[] blob);
}