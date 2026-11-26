package ai.paai.link.core.crypto

/** iOS: pure-Kotlin primitives (verified against Bouncy Castle and RFC / Noise vectors on the JVM). */
internal actual object PlatformCrypto {
    actual fun x25519(privateKey: ByteArray, publicKey: ByteArray): ByteArray = PortableCrypto.x25519(privateKey, publicKey)
    actual fun x25519Base(privateKey: ByteArray): ByteArray = PortableCrypto.x25519Base(privateKey)
    actual fun aeadEncrypt(key: ByteArray, nonce: ByteArray, ad: ByteArray, plaintext: ByteArray): ByteArray =
        PortableCrypto.aeadEncrypt(key, nonce, ad, plaintext)
    actual fun aeadDecrypt(key: ByteArray, nonce: ByteArray, ad: ByteArray, ciphertext: ByteArray): ByteArray =
        PortableCrypto.aeadDecrypt(key, nonce, ad, ciphertext)
    actual fun sha256(parts: Array<out ByteArray>): ByteArray = PortableCrypto.sha256(*parts)
    actual fun hmacSha256(key: ByteArray, parts: Array<out ByteArray>): ByteArray = PortableCrypto.hmacSha256(key, *parts)
}
