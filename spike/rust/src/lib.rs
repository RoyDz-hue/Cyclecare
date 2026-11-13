use aes_gcm::{aead::{Aead, KeyInit}, Aes256Gcm, Key, Nonce};
use base64::{engine::general_purpose::STANDARD as B64, Engine as _};
use rusqlite::Connection;
use sha2::{Digest, Sha256};

#[no_mangle]
pub extern "C" fn cyclecare_spike_probe() -> i32 {
    42
}

fn check_pass(pass: &str) {
    assert!(!pass.contains('\'') && !pass.contains('"'), "passphrase must be key-safe");
}

fn open_keyed(db: &str, pass: &str) -> Connection {
    check_pass(pass);
    let conn = Connection::open(db).expect("open db");
    conn.execute_batch(&format!("PRAGMA key = '{}';", pass)).expect("pragma key");
    conn
}

fn introspect(conn: &Connection) {
    for p in ["cipher_version", "kdf_iter", "cipher_page_size", "cipher_hmac_algorithm", "cipher_kdf_algorithm"] {
        let v: String = conn.query_row(&format!("PRAGMA {}", p), [], |r| r.get(0))
            .unwrap_or_else(|_| "<unset>".to_string());
        println!("CIPHER {} = {}", p, v);
    }
}

pub fn cmd_introspect(db: &str, pass: &str) {
    let conn = open_keyed(db, pass);
    introspect(&conn);
}

pub fn cmd_verify(db: &str, pass: &str) {
    let conn = open_keyed(db, pass);
    introspect(&conn);
    let expected: [(&str, i64); 8] = [
        ("cycle_settings", 1), ("onboarding_profiles", 1), ("cycle_day_logs", 3),
        ("health_sync_revisions", 1), ("health_sync_outbox", 1), ("health_sync_cursors", 1),
        ("health_sync_conflicts", 1), ("room_master_table", 1),
    ];
    let mut ok = true;
    for (table, want) in expected {
        let got: i64 = conn.query_row(&format!("SELECT count(*) FROM {}", table), [], |r| r.get(0))
            .unwrap_or(-1);
        let pass_flag = got == want;
        ok &= pass_flag;
        println!("COUNT {} = {} (want {}) {}", table, got, want, if pass_flag { "OK" } else { "MISMATCH" });
    }
    let hash: String = conn.query_row("SELECT identity_hash FROM room_master_table", [], |r| r.get(0)).unwrap_or_default();
    println!("ROOM_IDENTITY_HASH {}", hash);
    println!("VERIFY {}", if ok { "PASS" } else { "FAIL" });
    assert!(ok, "fixture counts mismatch");
}

pub fn cmd_create(db: &str, pass: &str, sql_file: &str) {
    check_pass(pass);
    let _ = std::fs::remove_file(db);
    let conn = Connection::open(db).expect("create db");
    conn.execute_batch(&format!("PRAGMA key = '{}';", pass)).expect("pragma key");
    let sql = std::fs::read_to_string(sql_file).expect("read ddl");
    conn.execute_batch(&sql).expect("apply ddl");
    introspect(&conn);
    println!("CREATE_OK {}", db);
}

fn read_vectors(path: &str) -> Vec<(Vec<u8>, Vec<u8>, Vec<u8>, Vec<u8>)> {
    let mut out = Vec::new();
    for line in std::fs::read_to_string(path).expect("read vectors").lines() {
        let line = line.trim();
        if line.is_empty() { continue; }
        let f: Vec<&str> = line.split(':').collect();
        assert_eq!(f.len(), 4, "vector line needs 4 fields");
        out.push((B64.decode(f[0]).unwrap(), B64.decode(f[1]).unwrap(), B64.decode(f[2]).unwrap(), B64.decode(f[3]).unwrap()));
    }
    out
}

fn decrypt_check(key: &[u8], combined: &[u8], want_pt: &[u8], label: &str) {
    let cipher = Aes256Gcm::new(Key::<Aes256Gcm>::from_slice(key));
    let (iv, ct) = combined.split_at(12);
    let pt = cipher.decrypt(Nonce::from_slice(iv), ct).expect("gcm decrypt");
    assert_eq!(pt, want_pt, "{} plaintext mismatch", label);
}

pub fn cmd_aes_verify(kotlin_in: &str, rust_out: &str) {
    // Direction 1: Kotlin AES/GCM/NoPadding -> Rust aes-gcm
    let mut n = 0;
    for (key, _iv, pt, combined) in read_vectors(kotlin_in) {
        decrypt_check(&key, &combined, &pt, "kotlin->rust");
        n += 1;
    }
    println!("AES_KOTLIN_TO_RUST {} vectors OK", n);
    // Direction 2: deterministic Rust vectors for the Kotlin side to verify
    let mut lines = String::new();
    let key = [7u8; 32];
    for (i, pt) in [b"".to_vec(), b"cyclecare".to_vec(), vec![b'x'; 64], vec![0u8, 159, 146, 150], vec![b'q'; 1000]].into_iter().enumerate() {
        let mut iv = [0u8; 12];
        iv[11] = i as u8 + 1;
        let cipher = Aes256Gcm::new(Key::<Aes256Gcm>::from_slice(&key));
        let ct = cipher.encrypt(Nonce::from_slice(&iv), pt.as_ref()).expect("gcm encrypt");
        let mut combined = iv.to_vec();
        combined.extend_from_slice(&ct);
        lines.push_str(&format!("{}:{}:{}:{}\n", B64.encode(key), B64.encode(iv), B64.encode(&pt), B64.encode(&combined)));
    }
    std::fs::write(rust_out, lines).expect("write rust vectors");
    println!("AES_RUST_VECTORS_WRITTEN {}", rust_out);
}

pub fn cmd_namespace(uuid: &str, expected: &str) {
    let digest = Sha256::digest(uuid.as_bytes());
    let name = "cycle_care_owner_".to_string() + &digest.iter().map(|b| format!("{:02x}", b)).collect::<String>();
    println!("NAMESPACE {} -> {} (expected {}) {}", uuid, name, expected, if name == expected { "OK" } else { "MISMATCH" });
    assert_eq!(name, expected, "namespace mismatch");
}
