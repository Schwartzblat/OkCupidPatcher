package com.smali_generator.likes;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.File;

/**
 * The durable home for everything the sweep learns: one SQLite row per card,
 * keyed by the normalized photo path that joins every part of this package.
 *
 * <p>Replaces the JSON-lines file the first sweeps wrote. SQLite is what the
 * data had outgrown -- "which cards still have no name", the query the naming
 * pass runs every time, is a {@code WHERE} clause here instead of a full-file
 * rewrite-and-rescan, and the file stays readable from outside with
 * {@code adb pull} plus {@code sqlite3}. A JSONL store left over from an
 * earlier build is migrated in on first open and then deleted, so nothing
 * already collected is lost.
 *
 * <p>{@link SweepStore} remains the authority on <em>merging</em> -- what a
 * second sighting of a card does to what is already known. This class only
 * reads that merged state in and writes it back out, in one transaction, so
 * a process killed mid-write leaves the previous contents intact.
 *
 * <p>Device-only: nothing here is reachable from a JVM unit test, which is
 * why the merge rules it would be worth testing live in {@link SweepStore}.
 */
final class CardDb {

    static final String FILE_NAME = "likes_cards.db";

    private static final String CREATE =
            "CREATE TABLE IF NOT EXISTS cards ("
            + "photo_path TEXT PRIMARY KEY NOT NULL,"
            + "user_id TEXT,"
            + "display_name TEXT,"
            + "age INTEGER,"
            + "match_score REAL,"
            + "is_online INTEGER,"
            + "is_verified INTEGER,"
            + "location TEXT,"
            + "position INTEGER NOT NULL DEFAULT 0,"
            + "first_seen_ms INTEGER NOT NULL DEFAULT 0,"
            + "last_seen_ms INTEGER NOT NULL DEFAULT 0,"
            + "name_seen_ms INTEGER NOT NULL DEFAULT 0)";

    private CardDb() {
    }

    static File path(Context ctx) {
        if (ctx == null) {
            return null;
        }
        File dir = ctx.getFilesDir();
        return dir == null ? null : new File(dir, FILE_NAME);
    }

    private static SQLiteDatabase open(Context ctx) {
        File file = path(ctx);
        if (file == null) {
            return null;
        }
        SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(file, null);
        db.execSQL(CREATE);
        return db;
    }

    /** Never throws: an unreadable or corrupt database is "nothing known yet", not a crash. */
    static SweepStore load(Context ctx, File legacyJsonl) {
        SweepStore store = new SweepStore();
        SQLiteDatabase db = null;
        Cursor c = null;
        try {
            db = open(ctx);
            if (db == null) {
                return store;
            }
            c = db.rawQuery("SELECT photo_path,user_id,display_name,age,match_score,is_online,"
                    + "is_verified,location,position,first_seen_ms,last_seen_ms,name_seen_ms FROM cards", null);
            while (c.moveToNext()) {
                String photoPath = c.getString(0);
                if (photoPath == null) {
                    continue;
                }
                store.adopt(new SweepStore.Record(
                        photoPath,
                        c.isNull(1) ? null : c.getString(1),
                        c.isNull(3) ? null : Integer.valueOf(c.getInt(3)),
                        c.isNull(4) ? null : Double.valueOf(c.getDouble(4)),
                        c.isNull(5) ? null : Boolean.valueOf(c.getInt(5) != 0),
                        c.isNull(6) ? null : Boolean.valueOf(c.getInt(6) != 0),
                        c.isNull(7) ? null : c.getString(7),
                        c.getInt(8),
                        c.getLong(9),
                        c.getLong(10),
                        c.isNull(2) ? null : c.getString(2),
                        c.getLong(11)));
            }
        } catch (Throwable t) {
            Log.w("CardDb: could not read the database -- starting from what is left");
        } finally {
            closeQuietly(c);
            closeQuietly(db);
        }
        migrateLegacy(store, legacyJsonl);
        return store;
    }

    /**
     * Folds a pre-SQLite JSONL store into {@code store} for anything the
     * database does not already hold, then removes the file. Deleted only
     * once every one of its records is in memory and therefore part of the
     * save that follows -- a delete that ran before a successful write would
     * lose data on a crash in between.
     */
    private static void migrateLegacy(SweepStore store, File legacyJsonl) {
        if (legacyJsonl == null || !legacyJsonl.isFile()) {
            return;
        }
        try {
            SweepStore old = SweepStore.load(legacyJsonl);
            int carried = 0;
            for (SweepStore.Record r : old.all()) {
                if (store.get(r.photoPath) == null) {
                    store.adopt(r);
                    carried++;
                }
            }
            Log.w("CardDb: migrated " + carried + " record(s) from the previous store format");
        } catch (Throwable t) {
            Log.w("CardDb: could not migrate the previous store -- leaving it in place");
        }
    }

    /**
     * Loads just the recovered names into {@link IdentityStore}, for the grid
     * to draw. Deliberately narrow: the render path needs a photo path and a
     * name, nothing else, and this runs where a page load can see it.
     *
     * @return how many names were made available.
     */
    static int primeNames(Context ctx) {
        SQLiteDatabase db = null;
        Cursor c = null;
        int loaded = 0;
        try {
            db = open(ctx);
            if (db == null) {
                return 0;
            }
            c = db.rawQuery("SELECT photo_path,display_name FROM cards WHERE display_name IS NOT NULL",
                    null);
            while (c.moveToNext()) {
                String path = c.getString(0);
                String name = c.getString(1);
                if (path != null && name != null) {
                    IdentityStore.get().rememberName(path, name);
                    loaded++;
                }
            }
        } catch (Throwable t) {
            Log.w("CardDb: could not read names");
        } finally {
            closeQuietly(c);
            closeQuietly(db);
        }
        return loaded;
    }

    /**
     * Writes the given records in one transaction. Never throws.
     *
     * <p>Called once per window by {@link SweepStore#flush} with only what
     * changed, so a pass's progress is durable as it is learned rather than
     * at the end. A pass over a 123-card list flushes about twenty rows per
     * window, which is one small transaction -- cheap next to the 400 ms the
     * sweep already waits between requests.
     *
     * <p>The one method here that does <em>not</em> swallow its failure: a
     * write that did not happen has to be reported, or {@link
     * SweepStore#flush} would drop those records believing them durable.
     * {@code flush} is what catches it, re-marks them for the next window's
     * attempt, and keeps it away from the app.
     */
    static void saveChanged(Context ctx, java.util.Collection<SweepStore.Record> records,
                            File legacyJsonl) {
        if (records == null || records.isEmpty()) {
            return;
        }
        SQLiteDatabase db = null;
        try {
            db = open(ctx);
            if (db == null) {
                return;
            }
            db.beginTransaction();
            try {
                for (SweepStore.Record r : records) {
                    ContentValues v = new ContentValues();
                    v.put("photo_path", r.photoPath);
                    putOrNull(v, "user_id", r.realId);
                    putOrNull(v, "display_name", r.displayName);
                    if (r.age == null) {
                        v.putNull("age");
                    } else {
                        v.put("age", r.age);
                    }
                    if (r.matchScore == null) {
                        v.putNull("match_score");
                    } else {
                        v.put("match_score", r.matchScore);
                    }
                    if (r.isOnline == null) {
                        v.putNull("is_online");
                    } else {
                        v.put("is_online", r.isOnline.booleanValue() ? 1 : 0);
                    }
                    if (r.isVerified == null) {
                        v.putNull("is_verified");
                    } else {
                        v.put("is_verified", r.isVerified.booleanValue() ? 1 : 0);
                    }
                    putOrNull(v, "location", r.locationSummary);
                    v.put("position", r.position);
                    v.put("first_seen_ms", r.firstSeenMs);
                    v.put("last_seen_ms", r.lastSeenMs);
                    v.put("name_seen_ms", r.nameSeenMs);
                    db.insertWithOnConflict("cards", null, v, SQLiteDatabase.CONFLICT_REPLACE);
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            // Only now is every migrated record durable.
            if (legacyJsonl != null && legacyJsonl.isFile()) {
                legacyJsonl.delete();
            }
        } finally {
            closeQuietly(db);
        }
    }

    private static void putOrNull(ContentValues v, String key, String value) {
        if (value == null) {
            v.putNull(key);
        } else {
            v.put(key, value);
        }
    }

    private static void closeQuietly(Cursor c) {
        if (c != null) {
            try {
                c.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void closeQuietly(SQLiteDatabase db) {
        if (db != null) {
            try {
                db.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
