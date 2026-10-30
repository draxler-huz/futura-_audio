import re, sqlite3, json, sys
import os
ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "app", "src", "main", "java", "com", "example", "wakeupmusic", "library") + os.sep
db_src = open(ROOT + "db/LibraryDatabase.kt").read()
repo_src = open(ROOT + "db/LibraryRepository.kt").read()

# 1) Schema strings from LibraryDatabase.kt: triple-quoted blocks and single-line "CREATE ..." literals.
schema = [m.strip() for m in re.findall(r'"""(CREATE TABLE.*?)"""', db_src, re.S)]
schema += re.findall(r'^\s*"(CREATE INDEX [^"]+)",', db_src, re.M)
fts = re.search(r'FTS_SQL =\s*"([^"]+)"\s*\+\s*"([^"]+)"', db_src, re.S)
fts_sql = fts.group(1) + fts.group(2)
ts = re.search(r'TRACK_SELECT = """(.*?)"""', repo_src, re.S).group(1)

con = sqlite3.connect(":memory:")
con.execute("PRAGMA foreign_keys = ON")
for s in schema:
    con.execute(s)
con.execute(fts_sql)
print("schema statements:", len(schema), "| fts5 table created:", fts_sql[:30])

# Sample data
con.executescript("""
INSERT INTO artists(id,name) VALUES (1,'Ada Band'),(2,'Bo Rock');
INSERT INTO albums(id,name,album_artist) VALUES (1,'First Light','Ada Band'),(2,'Rocket Cat','Bo Rock');
INSERT INTO genres(id,name) VALUES (1,'Rock'),(2,'Jazz');
""")
rows = [
 # id, path, root, media, uri, folder, title, album, artist, genre, dur, added, rating, fav, modified, size
 (1,'/storage/emulated/0/Music/Songs/a.mp3','MEDIASTORE',1,'content://media/external/audio/media/1','/storage/emulated/0/Music/Songs','Sunrise',1,1,1,200000,1000,5,1,1,10),
 (2,'/storage/emulated/0/Music/Songs/b.mp3','MEDIASTORE',2,'content://media/external/audio/media/2','/storage/emulated/0/Music/Songs','Rocket Launch',2,2,1,180000,9999999999999,2,0,1,11),
 (3,'/storage/emulated/0/Music/Podcasts/c.mp3','MEDIASTORE',3,'content://media/external/audio/media/3','/storage/emulated/0/Music/Podcasts',"It's 100% fun",None,None,None,900000,500,0,0,1,12),
]
for r in rows:
    con.execute("""INSERT INTO tracks(id,path,root,media_id,uri,folder_path,title,album_id,artist_id,genre_id,duration_ms,added_ms,rating,favorite,date_modified_ms,size_bytes,seen_gen)
                   VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,1)""", r)
    con.execute("INSERT INTO track_fts(rowid,title,artist,album,genre,folder) VALUES (?,?,?,?,?,?)",
                (r[0], r[6], {1:'Ada Band',2:'Bo Rock'}.get(r[8],''), {1:'First Light',2:'Rocket Cat'}.get(r[7],''),
                 {1:'Rock'}.get(r[9],''), r[5]))
con.execute("INSERT INTO play_stats(track_id,play_count,last_played_ms) VALUES (1,7,5000)")

def q(sql, args=()):
    return con.execute(sql, args).fetchall()

fails = 0
def check(name, cond):
    global fails
    print(("PASS " if cond else "FAIL ") + name)
    fails += (not cond)

# TRACK_SELECT columns must map to the names readTrack() reads.
cur = con.execute(ts + " WHERE 1 = 1 ORDER BY t.title COLLATE NOCASE, t.id LIMIT 20000")
names = [d[0] for d in cur.description]
need = ["id","path","uri","folder_path","title","root","album","album_id","artist","artist_id","genre","genre_id",
        "track_no","disc_no","year","duration_ms","added_ms","rating","favorite","play_count","last_played_ms","date_modified_ms"]
check("TRACK_SELECT exposes every column readTrack() reads: " + str([n for n in need if n not in names]), all(n in names for n in need))
titles = [r[names.index("title")] for r in cur.fetchall()]
check("All tracks sorted by title: " + str(titles), titles == sorted(titles, key=str.lower))

# Album / artist / genre filters
check("AlbumOf(1) returns 1 track", len(q(ts + " WHERE t.album_id = ?", ("1",))) == 1)
check("ArtistOf(2) returns 1 track", len(q(ts + " WHERE t.artist_id = ?", ("2",))) == 1)

# Folder recursive vs direct
p = "/storage/emulated/0/Music/Songs"
direct = q(ts + " WHERE t.folder_path = ?", (p,))
recursive = q(ts + " WHERE (t.folder_path = ? OR substr(t.folder_path, 1, ?) = ?)", (p, len(p)+1, p+"/"))
check("Folder direct = 2", len(direct) == 2)
check("Folder recursive includes podcasts only when root is music", len(q(ts + " WHERE (t.folder_path = ? OR substr(t.folder_path, 1, ?) = ?)", ("/storage/emulated/0/Music", len("/storage/emulated/0/Music")+1, "/storage/emulated/0/Music/"))) == 3)
check("Folder recursive does not match a sibling with a shared prefix", len(q(ts + " WHERE (t.folder_path = ? OR substr(t.folder_path, 1, ?) = ?)", ("/storage/emulated/0/Music/Song", len("/storage/emulated/0/Music/Song")+1, "/storage/emulated/0/Music/Song/"))) == 0)

# FTS search: token prefix AND
fts_match = '"rock"* "ca"*'
hits = q(ts + " WHERE t.id IN (SELECT rowid FROM track_fts WHERE track_fts MATCH ?)", (fts_match,))
check("FTS 'rock ca' matches track 2 (title Rocket, album Rocket Cat)", [h[0] for h in hits] == [2])
hits = q(ts + " WHERE t.id IN (SELECT rowid FROM track_fts WHERE track_fts MATCH ?)", ('"rock"*',))
check("FTS 'rock' matches 'Rocket Launch' and 'Rock' genre track", {h[0] for h in hits} >= {2,1})
hits = q(ts + " WHERE t.id IN (SELECT rowid FROM track_fts WHERE track_fts MATCH ?)", ('"ada"* "first"*',))
check("FTS multi-token AND finds Ada/First Light", [h[0] for h in hits] == [1])

# LIKE fallback query shape
like = "%" + "100\\%".replace("\\%","%") + "%"
check("LIKE fallback escapes % literal (query runs)", len(q(ts + " WHERE (t.title LIKE ? ESCAPE '\\' OR al.name LIKE ? ESCAPE '\\')", ("%100\\%%","%100\\%%"))) == 1)

# Smart playlist compiled SQL (from the unit test expectations, with the real ms values)
now = 1_000_000_000_000
smart = f"(t.added_ms >= {now - 30*86400000} AND t.rating >= 4)"
check("Smart 'added in last 30 days and rated 4+' -> none (track 1 added in 1970)", [h[0] for h in q(ts + " WHERE " + smart + " ORDER BY t.added_ms DESC")] == [])
smart2 = "(t.favorite = 1 OR COALESCE(ps.play_count, 0) = 0)"
check("Smart OR: favourite (track 1) or never played (tracks 2,3) -> [1,2,3]", sorted(h[0] for h in q(ts + " WHERE " + smart2)) == [1,2,3])
most = q(ts + " WHERE (COALESCE(ps.play_count, 0) > 0) ORDER BY COALESCE(ps.play_count, 0) DESC, t.title COLLATE NOCASE LIMIT 200")
check("Most Played returns the track with 7 plays", [h[0] for h in most] == [1])
never = q(ts + " WHERE (COALESCE(ps.play_count, 0) = 0) ORDER BY t.added_ms DESC LIMIT 500")
check("Never Played excludes the played track", 1 not in [h[0] for h in never])

# Play counting upsert
con.execute("INSERT INTO play_stats (track_id, play_count, last_played_ms) VALUES (?, 1, ?) ON CONFLICT(track_id) DO UPDATE SET play_count = play_count + 1, last_played_ms = excluded.last_played_ms", (2, 1234))
con.execute("INSERT INTO play_stats (track_id, play_count, last_played_ms) VALUES (?, 1, ?) ON CONFLICT(track_id) DO UPDATE SET play_count = play_count + 1, last_played_ms = excluded.last_played_ms", (2, 5678))
check("recordPlay upsert increments and keeps latest time", q("SELECT play_count,last_played_ms FROM play_stats WHERE track_id=2") == [(2,5678)])

# Playlist item ordering with duplicates, via rewriteOrdered shape
con.execute("INSERT INTO playlists(id,name,kind,builtin_key,rules_json,created_ms) VALUES (10,'Mix','MANUAL',NULL,'',1)")
con.execute("DELETE FROM playlist_items WHERE playlist_id=10")
for i, tid in enumerate([3,1,3]):
    con.execute("INSERT INTO playlist_items(playlist_id, position, track_id) VALUES (10, ?, ?)", (i, tid))
check("playlist keeps duplicate track in order", q("SELECT track_id FROM playlist_items WHERE playlist_id=10 ORDER BY position") == [(3,),(1,),(3,)])

# Queue move (rewrite) and cascade delete
con.execute("INSERT INTO queues(id,name,is_active,created_ms) VALUES (1,'Queue',1,0)")
con.execute("INSERT INTO queue_items(queue_id,position,track_id) VALUES (1,0,1),(1,1,2)")
con.execute("UPDATE queues SET is_active = CASE WHEN id = ? THEN 1 ELSE 0 END", (1,))
con.execute("DELETE FROM queue_items WHERE queue_id=1"); con.executemany("INSERT INTO queue_items(queue_id,position,track_id) VALUES (1,?,?)", [(0,2),(1,1)])
check("queue reorder persists order", q("SELECT track_id FROM queue_items WHERE queue_id=1 ORDER BY position") == [(2,),(1,)])
con.execute("DELETE FROM tracks WHERE id = 2")
check("deleting a track cascades out of queue_items and play_stats", q("SELECT COUNT(*) FROM queue_items WHERE track_id=2")[0][0] == 0 and q("SELECT COUNT(*) FROM play_stats WHERE track_id=2")[0][0] == 0)

# Delete unseen + orphan prune
con.execute("UPDATE tracks SET seen_gen = 5 WHERE id = 1")
con.execute("DELETE FROM tracks WHERE root='MEDIASTORE' AND seen_gen != 5")
con.execute("DELETE FROM albums WHERE id NOT IN (SELECT album_id FROM tracks WHERE album_id IS NOT NULL)")
check("prune keeps used albums only", [r[0] for r in q("SELECT id FROM albums ORDER BY id")] == [1])

# Folder rebuild logic replicated: ancestors up to storage roots, parent_path stored raw
FOLDER_ROOTS = {"", "/", "/storage", "/storage/emulated", "/storage/emulated/0"}
counts = {}
for fp, in q("SELECT folder_path FROM tracks"):
    counts[fp] = counts.get(fp, 0) + 1
allf = {}
for folder, c in counts.items():
    p_, own = folder, c
    while p_ and p_ not in FOLDER_ROOTS:
        allf[p_] = allf.get(p_, 0) + own; own = 0
        p_ = p_.rsplit("/", 1)[0] if "/" in p_ else ""
con.execute("DELETE FROM folders")
for path, n in allf.items():
    con.execute("INSERT INTO folders(path,parent_path,name,track_count) VALUES (?,?,?,?)", (path, path.rsplit("/",1)[0], path.rsplit("/",1)[1], n))
roots = q("SELECT path FROM folders WHERE parent_path IS NULL OR parent_path NOT IN (SELECT path FROM folders) ORDER BY name COLLATE NOCASE")
check("folder roots = top of the music tree", [r[0] for r in roots] == ["/storage/emulated/0/Music"])
kids = q("SELECT name FROM folders WHERE parent_path = ? ORDER BY name COLLATE NOCASE", ("/storage/emulated/0/Music",))
check("folder children after the delete step are exactly ['Songs'] (Podcasts track was pruned)", [k[0] for k in kids] == ["Songs"])

print("\nFAILURES:", fails)
sys.exit(1 if fails else 0)
