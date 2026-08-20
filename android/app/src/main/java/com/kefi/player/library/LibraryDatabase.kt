package com.kefi.player.library

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * The app's local database for user playlists, their ordered items, and favourites — all keyed
 * by relative path (see [PlaylistItem.path] for why). Deliberately separate from
 * [com.kefi.player.PlaybackStateStore], which is a SharedPreferences file (name: kefi.player)
 * whose `clear()` wipes everything in it; nothing in this class or its tables reads from or
 * writes to that file, and nothing in `PlaybackStateStore` should ever be asked to touch this
 * one either.
 *
 * `version = 1`: this is the schema's first release, so there is nothing to migrate from and no
 * `Migration` is written (the plan's Must NOT Have forbids pre-emptive migration scaffolding for
 * a version-1 database). `exportSchema` is left at Room's default (`true`) rather than disabled:
 * `android/app/build.gradle`'s `kapt.arguments` block, which sets `room.schemaLocation`, only
 * has something to export once an `@Database` exists, so leaving this at its default is
 * what makes that already-wired kapt argument do anything at all.
 */
@Database(
    entities = [Playlist::class, PlaylistItem::class, FavoriteTrack::class],
    version = 1,
)
abstract class LibraryDatabase : RoomDatabase() {

    abstract fun libraryDao(): LibraryDao

    companion object {

        /** Exact file name required by the playlists-tab plan; see [Room.databaseBuilder] below. */
        private const val DATABASE_NAME = "kefi-library.db"

        @Volatile
        private var instance: LibraryDatabase? = null

        /**
         * Double-checked-locking singleton — the idiom Room's own documentation uses for
         * exactly this: the cheap unsynchronized check on the common path (instance already
         * built), falling back to a `synchronized` block only the first time, so two threads
         * racing to build the database on first access cannot each create their own separate
         * connection onto `kefi-library.db`.
         *
         * Takes a [Context] rather than assuming `com.kefi.player.App.instance`, so this class
         * has no dependency in the other direction — callers (e.g. `PlayerPlugin`, which already
         * reaches a `Context` via `App.instance`) pass whichever `Context` they have; `getInstance`
         * stores [Context.getApplicationContext] rather than the passed-in `Context` itself, so
         * the held reference always outlives whatever Activity/Service happened to call this
         * first.
         */
        fun getInstance(context: Context): LibraryDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LibraryDatabase::class.java,
                    DATABASE_NAME,
                ).build().also { instance = it }
            }
    }
}
