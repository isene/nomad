// HyperList .p.hl encryption — byte-for-byte compatible with desktop scribe
// (src/buffer.rs) and the Ruby `hyperlist` app, so the same encrypted files
// (`.p.hl`, `.pass.hl`, …) open on the laptop and the phone.
//
//   plain → "ENC:" + base64(salt[16] ‖ iv[16] ‖ aes-256-cbc(pkcs7(plain)))
//   key  = PBKDF2-HMAC-SHA256(password, salt, 10000, 32)
//
// The IV is stored alongside the salt (NOT the openssl `Salted__` envelope),
// so each blob is independently decryptable. Mirrored verbatim from scribe.

use aes::Aes256;
use base64::Engine as _;
use cbc::{Decryptor, Encryptor};
use cipher::{block_padding::Pkcs7, BlockDecryptMut, BlockEncryptMut, KeyIvInit};
use hmac::Hmac;
use sha2::Sha256;

type Enc = Encryptor<Aes256>;
type Dec = Decryptor<Aes256>;

fn derive_key(password: &str, salt: &[u8]) -> [u8; 32] {
    let mut key = [0u8; 32];
    pbkdf2::pbkdf2::<Hmac<Sha256>>(password.as_bytes(), salt, 10000, &mut key)
        .expect("pbkdf2 cannot fail for 32-byte output");
    key
}

/// True if `content` is an encrypted HyperList blob (first non-space text is
/// the `ENC:` marker). Cheap; used to decide whether to prompt for a password.
#[uniffi::export]
pub fn is_encrypted(content: String) -> bool {
    content.trim_start().starts_with("ENC:")
}

/// Encrypt plaintext into the `ENC:` envelope. Returns None only on RNG
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

/// Decrypt an `ENC:` envelope. Returns None on a wrong password, a corrupt
/// blob, a missing marker, or non-UTF-8 plaintext.
#[uniffi::export]
pub fn hl_decrypt(ciphertext: String, password: String) -> Option<String> {
    let payload = ciphertext.trim().strip_prefix("ENC:")?;
    let blob = base64::engine::general_purpose::STANDARD
        .decode(payload)
        .ok()?;
    if blob.len() < 32 {
        return None;
    }
    let salt = &blob[0..16];
    let iv = &blob[16..32];
    let ct = &blob[32..];
    let key = derive_key(&password, salt);
    let pt = Dec::new(key.as_slice().into(), iv.into())
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

}
