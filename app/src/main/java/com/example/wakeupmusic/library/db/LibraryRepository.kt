package com.example.wakeupmusic.library.db

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.example.wakeupmusic.library.scan.ScanPolicy
import com.example.wakeupmusic.library.smart.SmartCompiler
import com.example.wakeupmusic.library.smart.SmartPlaylists
import com.example.wakeupmusic.library.smart.SmartRules
import com.example.wakeupmusic.library.tags.TagFields

/** Per-run caches so a scan does not look up the same artist, album or genre twice. */
class ScanCaches {
    val artists = HashMap<String, Long>()
    val albums = HashMap<String, Long>()
    val genres = HashMap<String, Long>()
}

/**
 * All reads and writes against library.db. Every function here is blocking I/O: call it from a background
 * thread, never from the main thread and never from the audio thread.
 */
class LibraryRepository(private val database: LibraryDatabase) {

    private val readable: SQLiteDatabase get() = database.readableDatabase
    private val writable: SQLiteDatabase get() = database.writableDatabase

    /** True when this device's SQLite has FTS5 and the index was created. Otherwise search uses LIKE. */
    val ftsEnabled: Boolean by lazy {
        readable.rawQuery("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'track_fts'", null)
            .use { it.moveToFirst() }
    }

    // ---------- built-in playlists ----------

    /** Inserts or refreshes the built-in smart playlists. Keeps their rules in step with the code. */
    fun ensureBuiltinPlaylists() {
        val w = writable
        SmartPlaylists.builtins().forEachIndexed { index, b ->
            val cv = ContentValues().apply {
                put("name", b.name)
                put("kind", KIND_SMART)
                put("rules_json", b.rules.toJson())
                put("created_ms", index.toLong())
            }
            val changed = w.update("playlists", cv, "builtin_key = ?", arrayOf(b.key))
            if (changed == 0) {
                cv.put("builtin_key", b.key)
                w.insertOrThrow("playlists", null, cv)
            }
        }
    }

    // ---------- track queries ----------

    /** Tracks for a list screen. Sorting and filtering use whitelisted SQL and bound arguments only. */
    fun tracks(filter: TrackFilter, sort: SortKey = SortKey.TITLE, limit: Int = MAX_LIST_ROWS): List<TrackRow> {
        val args = ArrayList<String>()
        val where = whereFor(filter, args)
        return queryTracks("$TRACK_SELECT WHERE $where ORDER BY ${sort.sql} LIMIT $limit", args.toTypedArray())
    }

    fun trackById(id: Long): TrackRow? =
        queryTracks("$TRACK_SELECT WHERE t.id = ?", arrayOf(id.toString())).firstOrNull()

    /** Returns the tracks in the given order. Duplicate ids are kept, because a playlist may hold a track twice. */
    fun tracksInOrder(ids: List<Long>): List<TrackRow> {
        if (ids.isEmpty()) return emptyList()
        val byId = HashMap<Long, TrackRow>(ids.size)
        for (chunk in ids.distinct().chunked(400)) {
            val marks = chunk.joinToString(",") { "?" }
            queryTracks("$TRACK_SELECT WHERE t.id IN ($marks)", chunk.map { it.toString() }.toTypedArray())
                .forEach { byId[it.id] = it }
        }
        return ids.mapNotNull { byId[it] }
    }

    fun trackCount(): Int = readable.rawQuery("SELECT COUNT(*) FROM tracks", null).use { it.moveToFirst(); it.getInt(0) }

    /** Tracks in a smart or manual playlist, in the playlist's own order. */
    fun playlistTracks(p: PlaylistRow): List<TrackRow> {
        if (p.kind == KIND_SMART) {
            val rules = SmartRules.fromJson(p.rulesJson) ?: return emptyList()
            val args = ArrayList<String>()
            val where = try {
                SmartCompiler.where(rules, args, System.currentTimeMillis())
            } catch (e: IllegalArgumentException) {
                return emptyList()
            }
            return queryTracks(
                "$TRACK_SELECT WHERE $where ORDER BY ${rules.sort.sql} LIMIT ${rules.limit}",
                args.toTypedArray(),
            )
        }
        return tracksInOrder(playlistTrackIds(p.id))
    }

    private fun whereFor(filter: TrackFilter, args: MutableList<String>): String = when (filter) {
        TrackFilter.All -> "1 = 1"
        is TrackFilter.AlbumOf -> {
            args += filter.id.toString()
            "t.album_id = ?"
        }
        is TrackFilter.ArtistOf -> {
            args += filter.id.toString()
            "t.artist_id = ?"
        }
        is TrackFilter.GenreOf -> {
            args += filter.id.toString()
            "t.genre_id = ?"
        }
        is TrackFilter.FolderOf -> {
            args += filter.path
            if (filter.recursive) {
                // Matches the folder itself and everything below it, without LIKE escaping issues.
                args += (filter.path.length + 1).toString()
                args += filter.path + "/"
                "(t.folder_path = ? OR substr(t.folder_path, 1, ?) = ?)"
            } else {
                "t.folder_path = ?"
            }
        }
        is TrackFilter.Search -> searchWhere(filter.text, args)
    }

    private fun searchWhere(text: String, args: MutableList<String>): String {
        val tokens = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return "1 = 1"
        if (ftsEnabled) {
            // Each token is a quoted prefix term. Terms are ANDed, so "rock ca" finds "Rocket Cat".
            args += tokens.joinToString(" ") { "\"" + it.replace("\"", "\"\"") + "\"*" }
            return "t.id IN (SELECT rowid FROM track_fts WHERE track_fts MATCH ?)"
        }
        return tokens.joinToString(" AND ", prefix = "(", postfix = ")") { token ->
            val like = "%" + likeEscape(token) + "%"
            args += listOf(like, like, like, like, like)
            "(t.title LIKE ? ESCAPE '\\' OR al.name LIKE ? ESCAPE '\\' OR ar.name LIKE ? ESCAPE '\\' " +
                "OR g.name LIKE ? ESCAPE '\\' OR t.folder_path LIKE ? ESCAPE '\\')"
        }
    }

    private fun queryTracks(sql: String, args: Array<String>): List<TrackRow> =
        readable.rawQuery(sql, args).use { c -> buildList(c.count) { while (c.moveToNext()) add(readTrack(c)) } }

    // ---------- albums, artists, genres, folders ----------

    fun albums(): List<NamedRow> = namedRows(
        "SELECT al.id, al.name, COUNT(t.id), al.album_artist FROM albums al " +
            "JOIN tracks t ON t.album_id = al.id GROUP BY al.id ORDER BY al.name COLLATE NOCASE",
    )

    fun artists(): List<NamedRow> = namedRows(
        "SELECT ar.id, ar.name, COUNT(t.id), '' FROM artists ar " +
            "JOIN tracks t ON t.artist_id = ar.id GROUP BY ar.id ORDER BY ar.name COLLATE NOCASE",
    )

    fun genres(): List<NamedRow> = namedRows(
        "SELECT g.id, g.name, COUNT(t.id), '' FROM genres g " +
            "JOIN tracks t ON t.genre_id = g.id GROUP BY g.id ORDER BY g.name COLLATE NOCASE",
    )

    private fun namedRows(sql: String): List<NamedRow> =
        readable.rawQuery(sql, null).use { c ->
            buildList { while (c.moveToNext()) add(NamedRow(c.getLong(0), c.getString(1) ?: "", c.getInt(2), c.getString(3) ?: "")) }
        }

    /** Child folders of [parent], or the top-level folders when [parent] is null. */
    fun folderChildren(parent: String?): List<FolderRow> {
        val sql = if (parent == null) {
            "SELECT path, parent_path, name, track_count FROM folders " +
                "WHERE parent_path IS NULL OR parent_path NOT IN (SELECT path FROM folders) ORDER BY name COLLATE NOCASE"
        } else {
            "SELECT path, parent_path, name, track_count FROM folders WHERE parent_path = ? ORDER BY name COLLATE NOCASE"
        }
        val args: Array<String> = if (parent == null) emptyArray() else arrayOf(parent)
        return readable.rawQuery(sql, args).use { c ->
            buildList { while (c.moveToNext()) add(FolderRow(c.getString(0), c.getString(1), c.getString(2), c.getInt(3))) }
        }
    }

    /** Rebuilds the folders table from the tracks. Folders between a track and the storage root are kept, for the tree. */
    fun rebuildFolders() {
        transaction { w ->
            val counts = HashMap<String, Int>()
            w.rawQuery("SELECT folder_path, COUNT(*) FROM tracks GROUP BY folder_path", null).use { c ->
                while (c.moveToNext()) counts[c.getString(0)] = c.getInt(1)
            }
            val all = LinkedHashMap<String, Int>()
            for ((folder, count) in counts) {
                var p = folder
                var own = count
                while (p.isNotEmpty() && p !in FOLDER_ROOTS) {
                    all[p] = (all[p] ?: 0) + own
                    own = 0
                    p = p.substringBeforeLast('/', "")
                }
            }
            w.delete("folders", null, null)
            val insert = w.compileStatement("INSERT INTO folders (path, parent_path, name, track_count) VALUES (?, ?, ?, ?)")
            for ((path, n) in all) {
                insert.clearBindings()
                insert.bindString(1, path)
                insert.bindString(2, path.substringBeforeLast('/', ""))
                insert.bindString(3, path.substringAfterLast('/'))
                insert.bindLong(4, n.toLong())
                insert.executeInsert()
            }
            insert.close()
        }
    }

    // ---------- playlists ----------

    fun playlists(): List<PlaylistRow> = readable.rawQuery(
        """SELECT p.id, p.name, p.kind, p.builtin_key, p.rules_json,
                  CASE WHEN p.kind = 'MANUAL'
                       THEN (SELECT COUNT(*) FROM playlist_items pi WHERE pi.playlist_id = p.id)
                       ELSE 0 END
           FROM playlists p
           ORDER BY CASE p.kind WHEN 'SMART' THEN 0 ELSE 1 END, p.created_ms, p.name COLLATE NOCASE""",
        null,
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(PlaylistRow(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4) ?: "", c.getInt(5)))
            }
        }
    }

    fun playlist(id: Long): PlaylistRow? = playlists().firstOrNull { it.id == id }

    fun playlistTrackIds(playlistId: Long): List<Long> = readable.rawQuery(
        "SELECT track_id FROM playlist_items WHERE playlist_id = ? ORDER BY position",
        arrayOf(playlistId.toString()),
    ).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }

    fun createPlaylist(name: String, trackIds: List<Long> = emptyList()): Long {
        var id = -1L
        transaction { w ->
            val cv = ContentValues().apply {
                put("name", name.trim().ifEmpty { "Playlist" })
                put("kind", KIND_MANUAL)
                putNull("builtin_key")
                put("rules_json", "")
                put("created_ms", System.currentTimeMillis())
            }
            id = w.insertOrThrow("playlists", null, cv)
            rewriteOrderedIn(w, "playlist_items", "playlist_id", id, trackIds)
        }
        return id
    }

    fun appendToPlaylist(playlistId: Long, trackIds: List<Long>) {
        if (trackIds.isEmpty()) return
        transaction { w ->
            val start = w.rawQuery(
                "SELECT COALESCE(MAX(position), -1) FROM playlist_items WHERE playlist_id = ?",
                arrayOf(playlistId.toString()),
            ).use { it.moveToFirst(); it.getLong(0) }
            val insert = w.compileStatement("INSERT INTO playlist_items (playlist_id, position, track_id) VALUES (?, ?, ?)")
            trackIds.forEachIndexed { i, trackId ->
                insert.clearBindings()
                insert.bindLong(1, playlistId)
                insert.bindLong(2, start + 1 + i)
                insert.bindLong(3, trackId)
                insert.executeInsert()
            }
            insert.close()
        }
    }

    /** Renames a manual playlist. Built-in playlists cannot be renamed. */
    fun renamePlaylist(id: Long, name: String) {
        writable.update(
            "playlists",
            ContentValues().apply { put("name", name.trim().ifEmpty { "Playlist" }) },
            "id = ? AND builtin_key IS NULL",
            arrayOf(id.toString()),
        )
    }

    /** Deletes a manual playlist. Built-in playlists cannot be deleted. */
    fun deletePlaylist(id: Long) {
        writable.delete("playlists", "id = ? AND builtin_key IS NULL", arrayOf(id.toString()))
    }

    /** Replaces the ordered items of a queue or playlist. Used by the queue store. */
    internal fun rewriteOrdered(table: String, keyColumn: String, key: Long, trackIds: List<Long>) {
        transaction { w -> rewriteOrderedIn(w, table, keyColumn, key, trackIds) }
    }

    private fun rewriteOrderedIn(w: SQLiteDatabase, table: String, keyColumn: String, key: Long, trackIds: List<Long>) {
        // table and keyColumn are constants from this file, never user input.
        w.delete(table, "$keyColumn = ?", arrayOf(key.toString()))
        val insert = w.compileStatement("INSERT INTO $table ($keyColumn, position, track_id) VALUES (?, ?, ?)")
        trackIds.forEachIndexed { i, trackId ->
            insert.clearBindings()
            insert.bindLong(1, key)
            insert.bindLong(2, i.toLong())
            insert.bindLong(3, trackId)
            insert.executeInsert()
        }
        insert.close()
    }

    // ---------- ratings, favourites, play counts ----------

    fun setRating(trackId: Long, rating: Int) {
        writable.update("tracks", ContentValues().apply { put("rating", rating.coerceIn(0, 5)) }, "id = ?", arrayOf(trackId.toString()))
    }

    fun setFavorite(trackId: Long, favorite: Boolean) {
        writable.update(
            "tracks",
            ContentValues().apply { put("favorite", if (favorite) 1 else 0) },
            "id = ?",
            arrayOf(trackId.toString()),
        )
    }

    /** Counts one play of the track with this URI. Called from the playback service, on a background thread. */
    fun recordPlay(uri: String, nowMs: Long) {
        val w = writable
        val id = w.rawQuery("SELECT id FROM tracks WHERE uri = ? LIMIT 1", arrayOf(uri)).use {
            if (it.moveToFirst()) it.getLong(0) else -1L
        }
        if (id < 0) return
        w.execSQL(
            "INSERT INTO play_stats (track_id, play_count, last_played_ms) VALUES (?, 1, ?) " +
                "ON CONFLICT(track_id) DO UPDATE SET play_count = play_count + 1, last_played_ms = excluded.last_played_ms",
            arrayOf<Any?>(id, nowMs),
        )
    }

    // ---------- scan support (used by MediaScanner only) ----------

    fun existingTracks(root: String): HashMap<String, ExistingTrack> {
        val out = HashMap<String, ExistingTrack>()
        readable.rawQuery(
            "SELECT id, path, date_modified_ms, size_bytes, duration_ms FROM tracks WHERE root = ?",
            arrayOf(root),
        ).use { c ->
            while (c.moveToNext()) out[c.getString(1)] = ExistingTrack(c.getLong(0), c.getLong(2), c.getLong(3), c.getLong(4))
        }
        return out
    }

    /** Which root already indexes this path, or null. Used so SAF scans skip files MediaStore already has. */
    fun rootOfPath(path: String): String? = readable.rawQuery(
        "SELECT root FROM tracks WHERE path = ? LIMIT 1", arrayOf(path),
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    fun beginBatch() {
        writable.beginTransaction()
    }

    fun endBatch(success: Boolean) {
        val w = writable
        if (success) w.setTransactionSuccessful()
        w.endTransaction()
    }

    fun markSeen(ids: List<Long>, gen: Long) {
        if (ids.isEmpty()) return
        val stmt = writable.compileStatement("UPDATE tracks SET seen_gen = ? WHERE id = ?")
        for (id in ids) {
            stmt.clearBindings()
            stmt.bindLong(1, gen)
            stmt.bindLong(2, id)
            stmt.executeUpdateDelete()
        }
        stmt.close()
    }

    /** Inserts or updates one scanned file. Rating, favourite and added date are kept on update. */
    fun upsertScanned(s: ScanWrite, gen: Long, caches: ScanCaches): Long {
        val w = writable
        val artistId = s.artist.takeIf { it.isNotBlank() }?.let { getOrCreate(w, "artists", "name", it, caches.artists) }
        val genreId = s.genre.takeIf { it.isNotBlank() }?.let { getOrCreate(w, "genres", "name", it, caches.genres) }
        val albumId = s.album.takeIf { it.isNotBlank() }?.let { albumIdOf(w, it, s.artist, caches.albums) }

        val cv = ContentValues().apply {
            put("root", s.root)
            putNullableLong("media_id", s.mediaId)
            put("uri", s.uri)
            put("folder_path", s.folderPath)
            put("title", s.title)
            putNullableLong("album_id", albumId)
            putNullableLong("artist_id", artistId)
            putNullableLong("genre_id", genreId)
            put("track_no", s.trackNo)
            put("disc_no", s.discNo)
            put("year", s.year)
            put("duration_ms", s.durationMs)
            put("size_bytes", s.sizeBytes)
            put("date_modified_ms", s.dateModifiedMs)
            put("seen_gen", gen)
        }
        val existing = w.rawQuery("SELECT id FROM tracks WHERE path = ?", arrayOf(s.path)).use {
            if (it.moveToFirst()) it.getLong(0) else -1L
        }
        val id = if (existing >= 0) {
            w.update("tracks", cv, "id = ?", arrayOf(existing.toString()))
            existing
        } else {
            cv.put("path", s.path)
            cv.put("added_ms", System.currentTimeMillis())
            w.insertOrThrow("tracks", null, cv)
        }
        refreshFts(w, id)
        return id
    }

    /** Deletes the tracks of [root] that were not seen in generation [gen]. Pass gen = -1 to delete the whole root. */
    fun deleteUnseen(root: String, gen: Long): Int {
        val w = writable
        val ids = w.rawQuery(
            "SELECT id FROM tracks WHERE root = ? AND seen_gen != ?",
            arrayOf(root, gen.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }
        for (chunk in ids.chunked(400)) {
            val marks = chunk.joinToString(",") { "?" }
            val args = chunk.map { it.toString() }.toTypedArray()
            if (ftsEnabled) w.delete("track_fts", "rowid IN ($marks)", args)
            w.delete("tracks", "id IN ($marks)", args)
        }
        return ids.size
    }

    /** Removes artists, albums and genres that no track uses any more. */
    fun pruneOrphans() {
        val w = writable
        w.execSQL("DELETE FROM albums WHERE id NOT IN (SELECT album_id FROM tracks WHERE album_id IS NOT NULL)")
        w.execSQL("DELETE FROM artists WHERE id NOT IN (SELECT artist_id FROM tracks WHERE artist_id IS NOT NULL)")
        w.execSQL("DELETE FROM genres WHERE id NOT IN (SELECT genre_id FROM tracks WHERE genre_id IS NOT NULL)")
    }

    // ---------- scan rules ----------

    fun scanRules(): List<ScanRuleRow> = readable.rawQuery(
        "SELECT id, kind, value, enabled FROM scan_rules ORDER BY kind, value", null,
    ).use { c ->
        buildList { while (c.moveToNext()) add(ScanRuleRow(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3) != 0)) }
    }

    fun addRule(kind: String, value: String) {
        writable.insertWithOnConflict(
            "scan_rules",
            null,
            ContentValues().apply { put("kind", kind); put("value", value); put("enabled", 1) },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    fun removeRule(id: Long) {
        writable.delete("scan_rules", "id = ?", arrayOf(id.toString()))
    }

    fun setMinDurationMs(ms: Long) {
        val w = writable
        w.delete("scan_rules", "kind = 'MIN_DURATION_MS'", null)
        w.insertOrThrow(
            "scan_rules", null,
            ContentValues().apply { put("kind", "MIN_DURATION_MS"); put("value", ms.toString()); put("enabled", 1) },
        )
    }

    fun scanPolicy(): ScanPolicy {
        val rules = scanRules().filter { it.enabled }
        return ScanPolicy(
            whitelist = rules.filter { it.kind == "WHITELIST" }.map { it.value },
            blacklist = rules.filter { it.kind == "BLACKLIST" }.map { it.value },
            minDurationMs = rules.firstOrNull { it.kind == "MIN_DURATION_MS" }?.value?.toLongOrNull()
                ?: ScanPolicy.DEFAULT_MIN_DURATION_MS,
        )
    }

    fun safRoots(): List<String> = scanRules().filter { it.kind == "SAF_ROOT" }.map { it.value }

    // ---------- tag edits (used by TagEditor) ----------

    /** Applies the non-null fields to the row. A blank artist, album or genre clears that link. */
    fun applyTagsToRow(trackId: Long, f: TagFields) {
        val row = trackById(trackId) ?: return
        val w = writable
        val artistName = f.artist ?: row.artist
        val albumName = f.album ?: row.album
        val genreName = f.genre ?: row.genre
        val caches = ScanCaches()
        val artistId = artistName.takeIf { it.isNotBlank() }?.let { getOrCreate(w, "artists", "name", it, caches.artists) }
        val genreId = genreName.takeIf { it.isNotBlank() }?.let { getOrCreate(w, "genres", "name", it, caches.genres) }
        val albumId = albumName.takeIf { it.isNotBlank() }?.let { albumIdOf(w, it, artistName, caches.albums) }
        val cv = ContentValues().apply {
            put("title", f.title?.takeIf { it.isNotBlank() } ?: row.title)
            putNullableLong("artist_id", artistId)
            putNullableLong("album_id", albumId)
            putNullableLong("genre_id", genreId)
            put("track_no", f.trackNo ?: row.trackNo)
            put("disc_no", f.discNo ?: row.discNo)
            put("year", f.year ?: row.year)
        }
        w.update("tracks", cv, "id = ?", arrayOf(trackId.toString()))
        refreshFts(w, trackId)
    }

    fun saveTagBackups(batchId: Long, rows: List<TrackRow>) {
        if (rows.isEmpty()) return
        transaction { w ->
            val now = System.currentTimeMillis()
            for (r in rows) {
                w.insertOrThrow("tag_backups", null, ContentValues().apply {
                    put("batch_id", batchId)
                    put("track_id", r.id)
                    put("old_json", TagFields.from(r).toJson())
                    put("created_ms", now)
                })
            }
        }
    }

    /** Backed-up tags for a batch: track id to the old values as JSON. */
    fun tagBackups(batchId: Long): List<Pair<Long, String>> = readable.rawQuery(
        "SELECT track_id, old_json FROM tag_backups WHERE batch_id = ? ORDER BY id",
        arrayOf(batchId.toString()),
    ).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0) to c.getString(1)) } }

    fun deleteTagBackups(batchId: Long) {
        writable.delete("tag_backups", "batch_id = ?", arrayOf(batchId.toString()))
    }

    // ---------- internals ----------

    private fun getOrCreate(w: SQLiteDatabase, table: String, column: String, value: String, cache: HashMap<String, Long>): Long =
        cache[value] ?: run {
            w.insertWithOnConflict(table, null, ContentValues().apply { put(column, value) }, SQLiteDatabase.CONFLICT_IGNORE)
            w.rawQuery("SELECT id FROM $table WHERE $column = ?", arrayOf(value)).use { it.moveToFirst(); it.getLong(0) }
                .also { cache[value] = it }
        }

    private fun albumIdOf(w: SQLiteDatabase, name: String, artist: String, cache: HashMap<String, Long>): Long {
        val key = name + "\u0001" + artist
        return cache[key] ?: run {
            w.insertWithOnConflict(
                "albums", null,
                ContentValues().apply { put("name", name); put("album_artist", artist) },
                SQLiteDatabase.CONFLICT_IGNORE,
            )
            w.rawQuery("SELECT id FROM albums WHERE name = ? AND album_artist = ?", arrayOf(name, artist))
                .use { it.moveToFirst(); it.getLong(0) }
                .also { cache[key] = it }
        }
    }

    /** Keeps the FTS row for a track in step with its joined text. A no-op when FTS5 is missing. */
    private fun refreshFts(w: SQLiteDatabase, id: Long) {
        if (!ftsEnabled) return
        val values: List<String> = w.rawQuery(
            "SELECT t.title, COALESCE(ar.name, ''), COALESCE(al.name, ''), COALESCE(g.name, ''), t.folder_path " +
                "FROM tracks t LEFT JOIN artists ar ON ar.id = t.artist_id LEFT JOIN albums al ON al.id = t.album_id " +
                "LEFT JOIN genres g ON g.id = t.genre_id WHERE t.id = ?",
            arrayOf(id.toString()),
        ).use { c -> if (c.moveToFirst()) (0 until 5).map { c.getString(it) ?: "" } else emptyList() }
        if (values.isEmpty()) return
        w.execSQL("DELETE FROM track_fts WHERE rowid = ?", arrayOf<Any?>(id))
        w.execSQL(
            "INSERT INTO track_fts (rowid, title, artist, album, genre, folder) VALUES (?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(id, values[0], values[1], values[2], values[3], values[4]),
        )
    }

    private inline fun transaction(block: (SQLiteDatabase) -> Unit) {
        val w = writable
        w.beginTransaction()
        try {
            block(w)
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    private fun readTrack(c: Cursor): TrackRow = TrackRow(
        id = c.long("id"),
        path = c.str("path"),
        uri = c.str("uri"),
        folderPath = c.str("folder_path"),
        title = c.str("title"),
        album = c.str("album"),
        albumId = c.optLong("album_id"),
        artist = c.str("artist"),
        artistId = c.optLong("artist_id"),
        genre = c.str("genre"),
        genreId = c.optLong("genre_id"),
        trackNo = c.int("track_no"),
        discNo = c.int("disc_no"),
        year = c.int("year"),
        durationMs = c.long("duration_ms"),
        addedMs = c.long("added_ms"),
        rating = c.int("rating"),
        favorite = c.int("favorite") != 0,
        playCount = c.int("play_count"),
        lastPlayedMs = c.long("last_played_ms"),
        dateModifiedMs = c.long("date_modified_ms"),
        root = c.str("root"),
    )

    companion object {
        const val MAX_LIST_ROWS = 20_000

        /** Folders at or above these paths are not shown in the tree. */
        private val FOLDER_ROOTS = setOf("", "/", "/storage", "/storage/emulated", "/storage/emulated/0")

        private const val TRACK_SELECT = """
            SELECT t.id, t.path, t.uri, t.folder_path, t.title, t.root,
                   COALESCE(al.name, '') AS album, t.album_id,
                   COALESCE(ar.name, '') AS artist, t.artist_id,
                   COALESCE(g.name, '') AS genre, t.genre_id,
                   t.track_no, t.disc_no, t.year, t.duration_ms, t.added_ms, t.rating, t.favorite,
                   COALESCE(ps.play_count, 0) AS play_count,
                   COALESCE(ps.last_played_ms, 0) AS last_played_ms,
                   t.date_modified_ms
            FROM tracks t
            LEFT JOIN albums al ON al.id = t.album_id
            LEFT JOIN artists ar ON ar.id = t.artist_id
            LEFT JOIN genres g ON g.id = t.genre_id
            LEFT JOIN play_stats ps ON ps.track_id = t.id
        """

        private fun likeEscape(s: String): String = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    }
}

private fun Cursor.long(name: String): Long = getLong(getColumnIndexOrThrow(name))
private fun Cursor.int(name: String): Int = getInt(getColumnIndexOrThrow(name))
private fun Cursor.str(name: String): String = getString(getColumnIndexOrThrow(name)) ?: ""
private fun Cursor.optLong(name: String): Long? {
    val i = getColumnIndexOrThrow(name)
    return if (isNull(i)) null else getLong(i)
}

private fun ContentValues.putNullableLong(key: String, value: Long?) {
    if (value == null) putNull(key) else put(key, value)
}
