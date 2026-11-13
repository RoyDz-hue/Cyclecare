//! HOST-ONLY scratch model of the SharedPreferences surface AuthPreferences
//! is written against. This is NOT Android SharedPreferences or
//! EncryptedSharedPreferences. What IS source-grounded (AOSP 14.0.0_r1
//! SharedPreferencesImpl.java, verified): EditorImpl.commit() runs
//! commitToMemory() FIRST (the batch lands in the in-memory map), then
//! enqueueDiskWrite(); the returned bool reports only the DISK write. So a
//! failed commit leaves memory CHANGED with the new values and the disk file
//! stale - and this model reproduces exactly that. Explicit remaining model
//! limitations, none of which the ported source paths depend on:
//! 1) apply() is modeled synchronously; Android persists it asynchronously
//!    (enqueueDiskWrite without a latch).
//! 2) Single-threaded: Android's mLock / Kotlin's synchronized(prefs) cross-
//!    thread exclusion is not modeled; &mut self is the exclusion here.
//! 3) Value coverage is Bool/Long(String)/String only - the full set the
//!    AuthPreferences health-sync slice uses; Int/Float/StringSet are absent.
//! 4) Re-open recovery is proved for an ordinary process restart via
//!    write-tmp-then-rename (rename atomicity). CRASH/POWER-LOSS durability
//!    is NOT claimed: there is no fsync/dirsync and no crash-fault injection
//!    at that scope.

use std::cell::RefCell;
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::rc::{Rc, Weak};

thread_local! {
    /// AOSP SharedPreferencesImpl parity: while any opener of a file is
    /// alive, every other opener of that file shares its ONE in-memory
    /// instance (Android keeps a per-file singleton). Scoped to the current
    /// THREAD - narrower than Android's per-process cache, matching this
    /// model's single-threaded limitation. Entries are Weak so that once
    /// every handle drops, the next open reloads from disk (ordinary
    /// restart recovery stays meaningful).
    static LIVE_FILES: RefCell<HashMap<PathBuf, Weak<RefCell<HashMap<String, PrefValue>>>>> =
        RefCell::new(HashMap::new());
}

#[derive(Debug, Clone, PartialEq)]
pub enum PrefValue {
    Bool(bool),
    Long(i64),
    Str(String),
}

pub struct ScratchPrefs {
    map: Rc<RefCell<HashMap<String, PrefValue>>>,
    file: Option<PathBuf>,
    /// TEST HOOK: models SharedPreferences commit() returning false
    /// (e.g. disk error). The staged batch is discarded; nothing persists.
    /// Per-handle (a device-wide fault would hit every handle; this hook is
    /// per-instance by design for targeted tests).
    pub inject_commit_failure: bool,
}

impl Default for ScratchPrefs {
    fn default() -> Self {
        ScratchPrefs { map: Rc::new(RefCell::new(HashMap::new())), file: None, inject_commit_failure: false }
    }
}

enum Op {
    Put(String, PrefValue),
    Remove(String),
}


/// Lexical path normalization for the live-file registry key: makes the
/// path absolute (against the current dir) and resolves "." / ".." without
/// touching the filesystem, so two spellings of one file share one live
/// instance. LIMIT: symlinks/hard links are NOT resolved (canonicalize
/// would fail for a file not yet created); two spellings that differ only
/// through a symlink still yield separate live instances.
fn normalize_key(path: &Path) -> PathBuf {
    let abs = if path.is_absolute() {
        path.to_path_buf()
    } else {
        std::env::current_dir().unwrap_or_default().join(path)
    };
    let mut out = PathBuf::new();
    for c in abs.components() {
        match c {
            std::path::Component::Prefix(p) => out.push(p.as_os_str()),
            std::path::Component::RootDir => out.push(c.as_os_str()),
            std::path::Component::CurDir => {}
            std::path::Component::ParentDir => { out.pop(); }
            std::path::Component::Normal(s2) => out.push(s2),
        }
    }
    out
}

impl ScratchPrefs {
    pub fn open_in_memory() -> Self { ScratchPrefs::default() }

    /// Open (or create) a file-backed store. Durability is via atomic
    /// write-tmp-then-rename on every successful commit/apply. While
    /// another live handle holds this path, the shared in-memory instance
    /// is returned (the file is NOT re-read - Android serves its cached
    /// instance); once all handles dropped, the next open reloads from disk.
    pub fn open_file(path: &Path) -> std::io::Result<Self> {
        let key = normalize_key(path);
        if let Some(shared) = LIVE_FILES.with(|l| l.borrow().get(&key).and_then(|w| w.upgrade())) {
            return Ok(ScratchPrefs { map: shared, file: Some(key), inject_commit_failure: false });
        }
        let mut fresh: HashMap<String, PrefValue> = HashMap::new();
        if path.exists() {
            let data = std::fs::read_to_string(path)?;
            for line in data.lines() {
                let mut it = line.splitn(3, '\t');
                let tag = it.next().unwrap_or("");
                let key = unescape(it.next().unwrap_or(""));
                let val = it.next().unwrap_or("");
                let v = match tag {
                    "B" => PrefValue::Bool(val == "1"),
                    "L" => PrefValue::Long(val.parse().map_err(|_| std::io::Error::new(std::io::ErrorKind::InvalidData, "bad long"))?),
                    "S" => PrefValue::Str(unescape(val)),
                    _ => continue,
                };
                fresh.insert(key, v);
            }
        }
        let map = Rc::new(RefCell::new(fresh));
        LIVE_FILES.with(|l| l.borrow_mut().insert(path.to_path_buf(), Rc::downgrade(&map)));
        Ok(ScratchPrefs { map, file: Some(path.to_path_buf()), inject_commit_failure: false })
    }

    pub fn get_bool(&self, key: &str, def: bool) -> bool {
        match self.map.borrow().get(key) { Some(PrefValue::Bool(b)) => *b, _ => def }
    }
    pub fn get_long(&self, key: &str, def: i64) -> i64 {
        match self.map.borrow().get(key) { Some(PrefValue::Long(l)) => *l, _ => def }
    }
    pub fn get_string(&self, key: &str) -> Option<String> {
        match self.map.borrow().get(key) { Some(PrefValue::Str(s)) => Some(s.clone()), _ => None }
    }

    pub fn edit(&mut self) -> Editor<'_> { Editor { prefs: self, ops: Vec::new() } }

    fn persist(&self) -> bool {
        let Some(path) = &self.file else { return true };
        let mut out = String::new();
        // Sorted for deterministic scratch files.
        let map = self.map.borrow();
        let mut entries: Vec<_> = map.iter().collect();
        entries.sort_by(|a, b| a.0.cmp(b.0));
        for (k, v) in entries {
            let (tag, val) = match v {
                PrefValue::Bool(b) => ("B", if *b { "1".into() } else { "0".into() }),
                PrefValue::Long(l) => ("L", l.to_string()),
                PrefValue::Str(s) => ("S", escape(s)),
            };
            out.push_str(tag);
            out.push('\t');
            out.push_str(&escape(k));
            out.push('\t');
            out.push_str(&val);
            out.push('\n');
        }
        // Append (never replace-extension): same-stem files must not
        // collide on a shared temp name.
        let tmp = PathBuf::from(format!("{}.tmp", path.display()));
        if std::fs::write(&tmp, out).is_err() { return false; }
        std::fs::rename(&tmp, path).is_ok()
    }
}

pub struct Editor<'a> {
    prefs: &'a mut ScratchPrefs,
    ops: Vec<Op>,
}

impl Editor<'_> {
    pub fn put_bool(self, k: &str, v: bool) -> Self { self.push(Op::Put(k.into(), PrefValue::Bool(v))) }
    pub fn put_long(self, k: &str, v: i64) -> Self { self.push(Op::Put(k.into(), PrefValue::Long(v))) }
    pub fn put_string(self, k: &str, v: &str) -> Self { self.push(Op::Put(k.into(), PrefValue::Str(v.into()))) }
    pub fn remove(self, k: &str) -> Self { self.push(Op::Remove(k.into())) }
    fn push(mut self, op: Op) -> Self { self.ops.push(op); self }

    /// SharedPreferences.Editor.commit(): AOSP 14 SharedPreferencesImpl
    /// .java parity - commitToMemory() lands the batch in memory FIRST, then
    /// the disk write runs and its result is the returned bool. A false
    /// result leaves memory CHANGED and the disk file stale.
    pub fn commit(self) -> bool {
        {
            let mut map = self.prefs.map.borrow_mut();
            for op in self.ops {
                match op {
                    Op::Put(k, v) => { map.insert(k, v); }
                    Op::Remove(k) => { map.remove(&k); }
                }
            }
        }
        if self.prefs.inject_commit_failure { return false; }
        self.prefs.persist()
    }

    /// SharedPreferences.Editor.apply(): in-memory apply plus asynchronous
    /// disk persist; callers observe no result. Modeled synchronously.
    pub fn apply(self) {
        {
            let mut map = self.prefs.map.borrow_mut();
            for op in self.ops {
                match op {
                    Op::Put(k, v) => { map.insert(k, v); }
                    Op::Remove(k) => { map.remove(&k); }
                }
            }
        }
        let _ = self.prefs.persist();
    }
}

fn escape(s: &str) -> String {
    // \r MUST round-trip: str::lines() strips a trailing \r.
    s.replace('\\', "\\\\").replace('\t', "\\t").replace('\n', "\\n").replace('\r', "\\r")
}
fn unescape(s: &str) -> String {
    let mut out = String::new();
    let mut chars = s.chars();
    while let Some(c) = chars.next() {
        if c == '\\' {
            match chars.next() {
                Some('t') => out.push('\t'),
                Some('n') => out.push('\n'),
                Some('r') => out.push('\r'),
                Some('\\') => out.push('\\'),
                Some(other) => { out.push('\\'); out.push(other); }
                None => out.push('\\'),
            }
        } else {
            out.push(c);
        }
    }
    out
}
