// HyperList .p.hl encryption — interoperates with BOTH desktop encryptors:
//
//   1. scribe (src/buffer.rs) + the Ruby `hyperlist` app:
//        plain → "ENC:" + base64(salt[16] ‖ iv[16] ‖ aes-256-cbc(pkcs7(plain)))
//        key   = PBKDF2-HMAC-SHA256(password, salt, 10000, 32)
//
//   2. the vim hyperlist plugin (`openssl aes-256-cbc -e -pbkdf2 -a -salt`),
//      which is what the user's real ~/.tasks/.p.hl uses:
//        plain → base64( "Salted__"[8] ‖ salt[8] ‖ aes-256-cbc(pkcs7(plain)) )
//        key‖iv = PBKDF2-HMAC-SHA256(password, salt, 10000, 48)  (key32 + iv16)
//        (openssl `enc -pbkdf2` defaults: SHA-256 PRF, 10000 iterations)
//
// On open we auto-detect the envelope; on save we re-encrypt in the SAME
// envelope (so a phone edit stays readable by whichever laptop tool owns the
// file). The base64 of "Salted__" always begins "U2FsdGVkX1", which is how we
// recognise the openssl form cheaply without decoding.

use aes::Aes256;
use base64::Engine as _;
use cbc::{Decryptor, Encryptor};
use cipher::{block_padding::Pkcs7, BlockDecryptMut, BlockEncryptMut, KeyIvInit};
use hmac::Hmac;
use sha2::Sha256;

type Enc = Encryptor<Aes256>;
type Dec = Decryptor<Aes256>;

/// base64 of the 8-byte "Salted__" magic always starts with this prefix.
const OPENSSL_B64_PREFIX: &str = "U2FsdGVkX1";

fn derive_key(password: &str, salt: &[u8]) -> [u8; 32] {
    let mut key = [0u8; 32];
    pbkdf2::pbkdf2::<Hmac<Sha256>>(password.as_bytes(), salt, 10000, &mut key)
        .expect("pbkdf2 cannot fail for 32-byte output");
    key
}

/// Derive openssl's key+iv block: 48 bytes = aes-256 key (32) ‖ iv (16).
fn derive_key_iv(password: &str, salt: &[u8]) -> ([u8; 32], [u8; 16]) {
    let mut buf = [0u8; 48];
    pbkdf2::pbkdf2::<Hmac<Sha256>>(password.as_bytes(), salt, 10000, &mut buf)
        .expect("pbkdf2 cannot fail for 48-byte output");
    let mut key = [0u8; 32];
    let mut iv = [0u8; 16];
    key.copy_from_slice(&buf[0..32]);
    iv.copy_from_slice(&buf[32..48]);
    (key, iv)
}

/// True if `content` is an encrypted HyperList blob in EITHER envelope. Cheap;
/// used to decide whether to prompt for a password.
#[uniffi::export]
pub fn is_encrypted(content: String) -> bool {
    let t = content.trim_start();
    t.starts_with("ENC:") || t.starts_with(OPENSSL_B64_PREFIX)
}

/// True only for the openssl `Salted__` envelope. The Kotlin shell uses this
/// to re-encrypt in the same format on save.
#[uniffi::export]
pub fn is_openssl_encrypted(content: String) -> bool {
    content.trim_start().starts_with(OPENSSL_B64_PREFIX)
}

/// Encrypt plaintext into the scribe `ENC:` envelope. Returns None only on RNG
/// failure (effectively never on Android).
#[uniffi::export]
pub fn hl_encrypt(plaintext: String, password: String) -> Option<String> {
    let mut salt = [0u8; 16];
    let mut iv = [0u8; 16];
    if getrandom::getrandom(&mut salt).is_err() {
        return None;
    }
    if getrandom::getrandom(&mut iv).is_err() {
        return None;
    }
    let key = derive_key(&password, &salt);
    let ct = Enc::new(key.as_slice().into(), &iv.into())
        .encrypt_padded_vec_mut::<Pkcs7>(plaintext.as_bytes());

    let mut combined = Vec::with_capacity(32 + ct.len());
    combined.extend_from_slice(&salt);
    combined.extend_from_slice(&iv);
    combined.extend_from_slice(&ct);
    Some(format!(
        "ENC:{}",
        base64::engine::general_purpose::STANDARD.encode(combined)
    ))
}

/// Encrypt plaintext into the openssl `Salted__` envelope, byte-compatible with
/// `openssl aes-256-cbc -e -pbkdf2 -a -salt` (the vim hyperlist plugin). Output
/// is base64 wrapped at 64 columns with a trailing newline, matching openssl.
#[uniffi::export]
pub fn hl_encrypt_openssl(plaintext: String, password: String) -> Option<String> {
    let mut salt = [0u8; 8];
    if getrandom::getrandom(&mut salt).is_err() {
        return None;
    }
    let (key, iv) = derive_key_iv(&password, &salt);
    let ct = Enc::new(key.as_slice().into(), &iv.into())
        .encrypt_padded_vec_mut::<Pkcs7>(plaintext.as_bytes());

    let mut blob = Vec::with_capacity(16 + ct.len());
    blob.extend_from_slice(b"Salted__");
    blob.extend_from_slice(&salt);
    blob.extend_from_slice(&ct);
    let b64 = base64::engine::general_purpose::STANDARD.encode(blob);

    // openssl `-a` wraps at 64 columns and ends with a newline.
    let mut out = String::with_capacity(b64.len() + b64.len() / 64 + 1);
    let bytes = b64.as_bytes();
    let mut i = 0;
    while i < bytes.len() {
        let end = (i + 64).min(bytes.len());
        out.push_str(&b64[i..end]);
        out.push('\n');
        i = end;
    }
    Some(out)
}

/// Decrypt either envelope. Returns None on a wrong password, a corrupt blob,
/// an unrecognised format, or non-UTF-8 plaintext.
#[uniffi::export]
pub fn hl_decrypt(ciphertext: String, password: String) -> Option<String> {
    let t = ciphertext.trim_start();
    if t.starts_with("ENC:") {
        decrypt_enc(t.trim(), &password)
    } else if t.starts_with(OPENSSL_B64_PREFIX) {
        decrypt_openssl(t, &password)
    } else {
        None
    }
}

/// scribe `ENC:` envelope: base64(salt[16] ‖ iv[16] ‖ ciphertext).
fn decrypt_enc(ciphertext: &str, password: &str) -> Option<String> {
    let payload = ciphertext.strip_prefix("ENC:")?;
    let blob = base64::engine::general_purpose::STANDARD
        .decode(payload)
        .ok()?;
    if blob.len() < 32 {
        return None;
    }
    let salt = &blob[0..16];
    let iv = &blob[16..32];
    let ct = &blob[32..];
    let key = derive_key(password, salt);
    let pt = Dec::new(key.as_slice().into(), iv.into())
        .decrypt_padded_vec_mut::<Pkcs7>(ct)
        .ok()?;
    String::from_utf8(pt).ok()
}

/// openssl `Salted__` envelope: base64("Salted__" ‖ salt[8] ‖ ciphertext),
/// possibly wrapped across lines (openssl `-a`).
fn decrypt_openssl(ciphertext: &str, password: &str) -> Option<String> {
    let b64: String = ciphertext.chars().filter(|c| !c.is_whitespace()).collect();
    let blob = base64::engine::general_purpose::STANDARD.decode(b64).ok()?;
    if blob.len() < 16 || &blob[0..8] != b"Salted__" {
        return None;
    }
    let salt = &blob[8..16];
    let ct = &blob[16..];
    let (key, iv) = derive_key_iv(password, salt);
    let pt = Dec::new(key.as_slice().into(), iv.as_slice().into())
        .decrypt_padded_vec_mut::<Pkcs7>(ct)
        .ok()?;
    String::from_utf8(pt).ok()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip() {
        let blob = hl_encrypt("master: hunter2\nbank: s3cr3t".into(), "pw".into()).unwrap();
        assert!(blob.starts_with("ENC:"));
        assert!(is_encrypted(blob.clone()));
        let back = hl_decrypt(blob, "pw".into()).unwrap();
        assert_eq!(back, "master: hunter2\nbank: s3cr3t");
    }

    #[test]
    fn wrong_password_fails() {
        let blob = hl_encrypt("secret".into(), "right".into()).unwrap();
        assert!(hl_decrypt(blob, "wrong".into()).is_none());
    }

    #[test]
    fn plaintext_is_not_flagged() {
        assert!(!is_encrypted("\tPersonal\n\t\tbuy milk".into()));
        assert!(is_encrypted("  ENC:abcd".into()));
    }

    #[test]
    fn unique_salt_iv_per_call() {
        // Same plaintext + password must not produce identical blobs.
        let a = hl_encrypt("x".into(), "p".into()).unwrap();
        let b = hl_encrypt("x".into(), "p".into()).unwrap();
        assert_ne!(a, b);
    }

    #[test]
    fn decrypt_independent_impl_blob() {
        // Blob produced by an independent implementation (Python PBKDF2 +
        // openssl AES-256-CBC) of the documented format — proves the core
        // reads blobs written by scribe / the Ruby app, not just its own.
        let blob = "ENC:5eu9olbz+06bFVDLeUS9vSJxgO64AlDL5dNeRS1kurmk0z5+x1vnKPr+tsdnNar7Z7xxz/VIjoZaAPVi+dGMYw==";
        let pt = hl_decrypt(blob.into(), "secret".into()).unwrap();
        assert_eq!(pt, "master: hunter2\nbank: s3cr3t\n");
    }

    #[test]
    fn decrypt_openssl_cli_blob() {
        // Produced by the EXACT vim-plugin command:
        //   printf 'hello world\nsecond line\n' \
        //     | openssl aes-256-cbc -e -pbkdf2 -a -salt -pass pass:testpassword
        // Proves we read the user's real ~/.tasks/.p.hl envelope.
        let blob = "U2FsdGVkX19EMO2mqlsCSLDsvL/TEE2KnUvyHyyoz1mQOvjS1a4Ea7clJGvoLT2G";
        assert!(is_encrypted(blob.into()));
        assert!(is_openssl_encrypted(blob.into()));
        let pt = hl_decrypt(blob.into(), "testpassword".into()).unwrap();
        assert_eq!(pt, "hello world\nsecond line\n");
    }

    #[test]
    fn openssl_roundtrip() {
        let plain = "master: hunter2\nbank: s3cr3t\n";
        let blob = hl_encrypt_openssl(plain.into(), "pw".into()).unwrap();
        assert!(is_openssl_encrypted(blob.clone()));
        assert!(is_encrypted(blob.clone()));
        let back = hl_decrypt(blob, "pw".into()).unwrap();
        assert_eq!(back, plain);
    }

    #[test]
    fn openssl_wrong_password_fails() {
        let blob = hl_encrypt_openssl("secret".into(), "right".into()).unwrap();
        assert!(hl_decrypt(blob, "wrong".into()).is_none());
    }
}
