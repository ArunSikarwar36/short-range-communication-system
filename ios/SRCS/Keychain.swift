import Foundation
import Security
import SrcsCore

/// The device identity (X25519 private key) lives in the Keychain, readable only on this device
/// after the first unlock. It is never written to app files or backups.
enum Keychain {
    private static let service = "ai.paai.srcs"
    private static let account = "identity-x25519"

    static func identityKey() -> KotlinByteArray {
        if let d = load(), d.count == 32 { return d.kotlinBytes }
        let key = SrcsController.Companion.shared.newPrivateKey()
        save(key.data)
        return key
    }

    static func reset() {
        let q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(q as CFDictionary)
    }

    private static func load() -> Data? {
        let q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var out: CFTypeRef?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess else { return nil }
        return out as? Data
    }

    private static func save(_ data: Data) {
        reset()
        let q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        SecItemAdd(q as CFDictionary, nil)
    }
}

extension Data {
    var kotlinBytes: KotlinByteArray {
        let out = KotlinByteArray(size: Int32(count))
        for (i, b) in enumerated() { out.set(index: Int32(i), value: Int8(bitPattern: b)) }
        return out
    }
}

extension KotlinByteArray {
    var data: Data {
        var d = Data(count: Int(size))
        for i in 0..<Int(size) { d[i] = UInt8(bitPattern: get(index: Int32(i))) }
        return d
    }
}
