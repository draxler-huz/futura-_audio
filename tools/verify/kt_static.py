import os
import re, os, sys
root = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
changed = []
for dp, _, fs in os.walk(root + "/app/src"):
    for f in fs:
        if f.endswith(".kt") and ("/library/" in dp + "/" or f in ("PlayerActivity.kt","PlayerScreen.kt","FormatTestScreen.kt","PermissionExplanations.kt","PlaybackService.kt","AlarmPlayback.kt","AlarmService.kt","AlarmActivity.kt","AlarmReceiver.kt","MainActivity.kt")):
            changed.append(os.path.join(dp, f))

def strip(src):
    # remove comments and string literal contents (keep quotes) for structural checks
    out, i, n = [], 0, len(src)
    while i < n:
        if src.startswith('"""', i):
            j = src.find('"""', i + 3); j = n if j < 0 else j + 3
            out.append('""'); i = j; continue
        if src.startswith('//', i):
            j = src.find('\n', i); i = n if j < 0 else j; continue
        if src.startswith('/*', i):
            j = src.find('*/', i); i = n if j < 0 else j + 2; continue
        if src[i] == '"':
            j = i + 1
            while j < n and src[j] != '"':
                j += 2 if src[j] == '\\' else 1
            out.append('""'); i = j + 1; continue
        if src[i] == "'":
            j = i + 1
            while j < n and src[j] != "'":
                j += 2 if src[j] == '\\' else 1
            out.append("''"); i = j + 1; continue
        out.append(src[i]); i += 1
    return "".join(out)

bad = 0
for p in changed:
    s = strip(open(p).read())
    for o, c in (("{", "}"), ("(", ")"), ("[", "]")):
        if s.count(o) != s.count(c):
            print(f"UNBALANCED {o}{c} {s.count(o)} vs {s.count(c)}: {p}"); bad += 1
print("bracket check:", "ok" if not bad else "FAILED", f"({len(changed)} files)")

# identifier audit: capitalised names used but neither imported, declared in the file, nor in the package
KNOWN_STD = {"String","Int","Long","Boolean","Float","Double","Char","Any","Unit","Nothing","List","Map","Set",
  "ArrayList","HashMap","LinkedHashMap","HashSet","Pair","Result","Exception","IllegalArgumentException",
  "UnsupportedOperationException","SecurityException","IllegalStateException","Regex","Array","Comparable","Iterable",
  "Triple","Throwable","Error","Runnable","Lazy","Volatile","Suppress","JvmStatic","Deprecated","Sequence","Collection",
  "MutableList","MutableMap","MutableSet","MutableStateFlow","StateFlow","Enum","Companion","Object","Byte","Short","Number",
  "Class","Override","SuppressLint","OptIn","Intent","Bundle","Uri","Context","CharSequence","SQLException","Cursor",
  "SQLiteDatabase","SQLiteOpenHelper","ContentValues","JSONObject","JSONArray","Executors","ExecutorService","AtomicBoolean",
  "Surface","Modifier","Text","Button","Column","Row","Box","Spacer","TextButton","OutlinedButton","Tab","HorizontalDivider",
  "DropdownMenu","DropdownMenuItem","AlertDialog","OutlinedTextField","ScrollableTabRow","SwipeToDismissBox",
  "SwipeToDismissBoxValue","MaterialTheme","LazyColumn","LaunchedEffect","Dispatchers","CoroutineScope","SupervisorJob"}
for p in changed:
    src = open(p).read()
    pkg = re.search(r'^package ([\w.]+)', src, re.M).group(1)
    imports = set(re.findall(r'^import ([\w.]+)(?: as \w+)?$', src, re.M))
    imported_names = {i.split('.')[-1] for i in imports}
    declared = set(re.findall(r'\b(?:class|object|interface|fun|val|var|enum class|data class|sealed interface|typealias)\s+([A-Z]\w*)', src))
    declared |= set(re.findall(r'(?:class|object|interface|enum class|data class|sealed interface)\s+(\w+)', src))
    code = strip(src)
    code = re.sub(r'^(import|package) .*$', '', code, flags=re.M)
    used = set(re.findall(r'\b([A-Z][A-Za-z0-9]+)\b', code))
    missing = sorted(u for u in used if u not in imported_names and u not in declared and u not in KNOWN_STD)
    # names that are in the same package need no import
    same_pkg = set()
    for q in os.listdir(os.path.dirname(p)):
        if q.endswith(".kt"):
            same_pkg.add(q[:-3])
    missing = [m for m in missing if m not in same_pkg]
    if missing:
        print(os.path.relpath(p, root), "->", missing)
print("identifier audit done")
