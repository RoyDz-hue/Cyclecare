//! HOST-ONLY scratch model of the SharedPreferences contract that
//! AuthPreferences is written against: batched Editor mutations applied
//! atomically by commit() (all-or-nothing), durable across re-open, and
//! commit() failure applies NOTHING. This is NOT Android
//! EncryptedSharedPreferences: the secure-store surface (readSecureString /
//! writeSecureString) stays a separate seam. Synthetic scratch data only;
//! used to prove the durable-write obligations of the health-sync consent
//! paths against a source-faithful implementation instead of in-memory fakes.

use std::collections::HashMap;
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, PartialEq)]
pub enum PrefValue {
    Bool(bool),
    Long(i64),
    Str(String),
}

#[derive(Default)]
pub struct ScratchPrefs {
    map: HashMap<String, PrefValue>,
    file: Option<PathBuf>,
    /// TEST HOOK: models SharedPreferences commit() returning false
    /// (e.g. disk error). The staged batch is discarded; nothing persists.
    pub inject_commit_failure: bool,
}

enum Op {
    Put(String, PrefValue),
    Remove(String),
}

impl ScratchPrefs {
    pub fn open_in_memory() -> Self { ScratchPrefs::default() }

    /// Open (or create) a file-backed store. Durability is via atomic
    /// write-tmp-then-rename on every successful commit/apply.
    pub fn open_file(path: &Path) -> std::io::Result<Self> {
        let mut p = ScratchPrefs { map: HashMap::new(), file: Some(path.to_path_buf()), inject_commit_failure: false };
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
                p.map.insert(key, v);
            }
        }
        Ok(p)
    }

    pub fn get_bool(&self, key: &str, def: bool) -> bool {
        match self.map.get(key) { Some(PrefValue::Bool(b)) => *b, _ => def }
    }
    pub fn get_long(&self, key: &str, def: i64) -> i64 {
        match self.map.get(key) { Some(PrefValue::Long(l)) => *l, _ => def }
    }
    pub fn get_string(&self, key: &str) -> Option<String> {
        match self.map.get(key) { Some(PrefValue::Str(s)) => Some(s.clone()), _ => None }
    }

    pub fn edit(&mut self) -> Editor<'_> { Editor { prefs: self, ops: Vec::new() } }

    fn persist(&self) -> bool {
        let Some(path) = &self.file else { return true };
        let mut out = String::new();
        // Sorted for deterministic scratch files.
        let mut entries: Vec<_> = self.map.iter().collect();
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
        let tmp = path.with_extension("tmp");
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

    /// SharedPreferences.Editor.commit(): applies the WHOLE batch atomically
    /// and reports success; on failure nothing is applied or persisted.
    pub fn commit(self) -> bool {
        if self.prefs.inject_commit_failure { return false; }
        let mut staged = self.prefs.map.clone();
        for op in &self.ops {
            match op {
                Op::Put(k, v) => { staged.insert(k.clone(), v.clone()); }
                Op::Remove(k) => { staged.remove(k); }
            }
        }
        let prev = std::mem::replace(&mut self.prefs.map, staged);
        if !self.prefs.persist() {
            self.prefs.map = prev; // persist failed -> roll back in-memory too
            return false;
        }
        true
    }

    /// SharedPreferences.Editor.apply(): in-memory apply plus asynchronous
    /// disk persist; callers observe no result. Modeled synchronously.
    pub fn apply(self) {
        for op in self.ops {
            match op {
                Op::Put(k, v) => { self.prefs.map.insert(k, v); }
                Op::Remove(k) => { self.prefs.map.remove(&k); }
            }
        }
        let _ = self.prefs.persist();
    }
}

fn escape(s: &str) -> String {
    s.replace('\\', "\\\\").replace('\t', "\\t").replace('\n', "\\n")
}
fn unescape(s: &str) -> String {
    let mut out = String::new();
    let mut chars = s.chars();
    while let Some(c) = chars.next() {
        if c == '\\' {
            match chars.next() {
                Some('t') => out.push('\t'),
                Some('n') => out.push('\n'),
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
