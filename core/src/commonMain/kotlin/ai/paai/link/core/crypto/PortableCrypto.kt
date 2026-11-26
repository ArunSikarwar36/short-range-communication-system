package ai.paai.link.core.crypto

/**
 * Pure-Kotlin X25519 (RFC 7748), ChaCha20-Poly1305 (RFC 8439) and SHA-256 / HMAC-SHA-256.
 * Used on iOS, where Bouncy Castle is not available. Written without secret-dependent branches
 * or table lookups (X25519 follows TweetNaCl, Poly1305 follows poly1305-donna-32).
 *
 * Verified on the JVM against Bouncy Castle with random inputs and against the RFC test vectors
 * (PortableCryptoTest), and on iOS against the RFC and Noise test vectors (commonTest).
 */
object PortableCrypto {
    // ---------------------------------------------------------------- SHA-256 / HMAC

    private val K = longArrayOf(
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
    ).map { it.toInt() }.toIntArray()

    private val H0 = longArrayOf(
        0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
    ).map { it.toInt() }.toIntArray()

    private class Sha256 {
        private val h = H0.copyOf()
        private val block = ByteArray(64)
        private var blockLen = 0
        private var total = 0L
        private val w = IntArray(64)

        fun update(data: ByteArray) {
            for (b in data) {
                block[blockLen++] = b
                if (blockLen == 64) { compress(); blockLen = 0 }
            }
            total += data.size
        }

        fun digest(): ByteArray {
            val bits = total * 8
            block[blockLen++] = 0x80.toByte()
            if (blockLen > 56) {
                while (blockLen < 64) block[blockLen++] = 0
                compress(); blockLen = 0
            }
            while (blockLen < 56) block[blockLen++] = 0
            for (i in 0 until 8) block[56 + i] = (bits ushr (56 - 8 * i)).toByte()
            compress()
            val out = ByteArray(32)
            for (i in 0 until 8) for (j in 0 until 4) out[i * 4 + j] = (h[i] ushr (24 - 8 * j)).toByte()
            return out
        }

        private fun compress() {
            for (i in 0 until 16) {
                w[i] = ((block[i * 4].toInt() and 0xff) shl 24) or ((block[i * 4 + 1].toInt() and 0xff) shl 16) or
                    ((block[i * 4 + 2].toInt() and 0xff) shl 8) or (block[i * 4 + 3].toInt() and 0xff)
            }
            for (i in 16 until 64) {
                val s0 = w[i - 15].rotateRight(7) xor w[i - 15].rotateRight(18) xor (w[i - 15] ushr 3)
                val s1 = w[i - 2].rotateRight(17) xor w[i - 2].rotateRight(19) xor (w[i - 2] ushr 10)
                w[i] = w[i - 16] + s0 + w[i - 7] + s1
            }
            var a = h[0]; var b = h[1]; var c = h[2]; var d = h[3]
            var e = h[4]; var f = h[5]; var g = h[6]; var hh = h[7]
            for (i in 0 until 64) {
                val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
                val ch = (e and f) xor (e.inv() and g)
                val t1 = hh + s1 + ch + K[i] + w[i]
                val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val t2 = s0 + maj
                hh = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
            }
            h[0] += a; h[1] += b; h[2] += c; h[3] += d; h[4] += e; h[5] += f; h[6] += g; h[7] += hh
        }
    }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val s = Sha256()
        for (p in parts) s.update(p)
        return s.digest()
    }

    fun hmacSha256(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val k = (if (key.size > 64) sha256(key) else key).copyOf(64)
        val ipad = ByteArray(64) { (k[it].toInt() xor 0x36).toByte() }
        val opad = ByteArray(64) { (k[it].toInt() xor 0x5c).toByte() }
        val inner = Sha256().apply { update(ipad); for (p in parts) update(p) }.digest()
        return Sha256().apply { update(opad); update(inner) }.digest()
    }

    // ---------------------------------------------------------------- ChaCha20-Poly1305

    private fun le32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8) or
            ((b[off + 2].toInt() and 0xff) shl 16) or ((b[off + 3].toInt() and 0xff) shl 24)

    private fun chachaBlock(key: ByteArray, counter: Int, nonce: ByteArray, out: ByteArray) {
        val s = IntArray(16)
        s[0] = 0x61707865; s[1] = 0x3320646e; s[2] = 0x79622d32; s[3] = 0x6b206574
        for (i in 0 until 8) s[4 + i] = le32(key, i * 4)
        s[12] = counter
        for (i in 0 until 3) s[13 + i] = le32(nonce, i * 4)
        val x = s.copyOf()
        fun qr(a: Int, b: Int, c: Int, d: Int) {
            x[a] += x[b]; x[d] = (x[d] xor x[a]).rotateLeft(16)
            x[c] += x[d]; x[b] = (x[b] xor x[c]).rotateLeft(12)
            x[a] += x[b]; x[d] = (x[d] xor x[a]).rotateLeft(8)
            x[c] += x[d]; x[b] = (x[b] xor x[c]).rotateLeft(7)
        }
        repeat(10) {
            qr(0, 4, 8, 12); qr(1, 5, 9, 13); qr(2, 6, 10, 14); qr(3, 7, 11, 15)
            qr(0, 5, 10, 15); qr(1, 6, 11, 12); qr(2, 7, 8, 13); qr(3, 4, 9, 14)
        }
        for (i in 0 until 16) {
            val v = x[i] + s[i]
            out[i * 4] = v.toByte(); out[i * 4 + 1] = (v ushr 8).toByte()
            out[i * 4 + 2] = (v ushr 16).toByte(); out[i * 4 + 3] = (v ushr 24).toByte()
        }
    }

    /** XOR [data] with the ChaCha20 key stream starting at block [counter] (RFC 8439 section 2.4). */
    fun chacha20(key: ByteArray, counter: Int, nonce: ByteArray, data: ByteArray): ByteArray {
        require(key.size == 32 && nonce.size == 12)
        val out = ByteArray(data.size)
        val ks = ByteArray(64)
        var ctr = counter
        var pos = 0
        while (pos < data.size) {
            chachaBlock(key, ctr++, nonce, ks)
            val n = minOf(64, data.size - pos)
            for (i in 0 until n) out[pos + i] = (data[pos + i].toInt() xor ks[i].toInt()).toByte()
            pos += n
        }
        return out
    }

    /** Poly1305 one-time authenticator (poly1305-donna-32). */
    fun poly1305(key: ByteArray, msg: ByteArray): ByteArray {
        require(key.size == 32)
        val m26 = 0x3ffffffL
        fun u32(b: ByteArray, off: Int) = le32(b, off).toLong() and 0xffffffffL
        val r0 = u32(key, 0) and 0x3ffffff
        val r1 = (u32(key, 3) ushr 2) and 0x3ffff03
        val r2 = (u32(key, 6) ushr 4) and 0x3ffc0ff
        val r3 = (u32(key, 9) ushr 6) and 0x3f03fff
        val r4 = (u32(key, 12) ushr 8) and 0x00fffff
        val s1 = r1 * 5; val s2 = r2 * 5; val s3 = r3 * 5; val s4 = r4 * 5
        var h0 = 0L; var h1 = 0L; var h2 = 0L; var h3 = 0L; var h4 = 0L
        val blk = ByteArray(17)
        var pos = 0
        while (pos < msg.size) {
            val n = minOf(16, msg.size - pos)
            blk.fill(0)
            msg.copyInto(blk, 0, pos, pos + n)
            val hibit: Long
            if (n == 16) hibit = 1L shl 24 else { blk[n] = 1; hibit = 0 }
            h0 += u32(blk, 0) and m26
            h1 += (u32(blk, 3) ushr 2) and m26
            h2 += (u32(blk, 6) ushr 4) and m26
            h3 += (u32(blk, 9) ushr 6) and m26
            h4 += (u32(blk, 12) ushr 8) or hibit
            val d0 = h0 * r0 + h1 * s4 + h2 * s3 + h3 * s2 + h4 * s1
            var d1 = h0 * r1 + h1 * r0 + h2 * s4 + h3 * s3 + h4 * s2
            var d2 = h0 * r2 + h1 * r1 + h2 * r0 + h3 * s4 + h4 * s3
            var d3 = h0 * r3 + h1 * r2 + h2 * r1 + h3 * r0 + h4 * s4
            var d4 = h0 * r4 + h1 * r3 + h2 * r2 + h3 * r1 + h4 * r0
            var c = d0 ushr 26; h0 = d0 and m26
            d1 += c; c = d1 ushr 26; h1 = d1 and m26
            d2 += c; c = d2 ushr 26; h2 = d2 and m26
            d3 += c; c = d3 ushr 26; h3 = d3 and m26
            d4 += c; c = d4 ushr 26; h4 = d4 and m26
            h0 += c * 5; c = h0 ushr 26; h0 = h0 and m26
            h1 += c
            pos += n
        }
        var c = h1 ushr 26; h1 = h1 and m26
        h2 += c; c = h2 ushr 26; h2 = h2 and m26
        h3 += c; c = h3 ushr 26; h3 = h3 and m26
        h4 += c; c = h4 ushr 26; h4 = h4 and m26
        h0 += c * 5; c = h0 ushr 26; h0 = h0 and m26
        h1 += c
        // Compute h + -p and select h or h - p in constant time.
        var g0 = h0 + 5; c = g0 ushr 26; g0 = g0 and m26
        var g1 = h1 + c; c = g1 ushr 26; g1 = g1 and m26
        var g2 = h2 + c; c = g2 ushr 26; g2 = g2 and m26
        var g3 = h3 + c; c = g3 ushr 26; g3 = g3 and m26
        var g4 = h4 + c - (1L shl 26)
        var mask = (g4 ushr 63) - 1 // all ones if g4 >= 0 (h >= p), else 0
        g0 = g0 and mask; g1 = g1 and mask; g2 = g2 and mask; g3 = g3 and mask; g4 = g4 and mask
        mask = mask.inv()
        h0 = (h0 and mask) or g0; h1 = (h1 and mask) or g1; h2 = (h2 and mask) or g2
        h3 = (h3 and mask) or g3; h4 = (h4 and mask) or g4
        val w0 = (h0 or (h1 shl 26)) and 0xffffffffL
        val w1 = ((h1 ushr 6) or (h2 shl 20)) and 0xffffffffL
        val w2 = ((h2 ushr 12) or (h3 shl 14)) and 0xffffffffL
        val w3 = ((h3 ushr 18) or (h4 shl 8)) and 0xffffffffL
        var f = w0 + u32(key, 16)
        val o0 = f and 0xffffffffL
        f = w1 + u32(key, 20) + (f ushr 32)
        val o1 = f and 0xffffffffL
        f = w2 + u32(key, 24) + (f ushr 32)
        val o2 = f and 0xffffffffL
        f = w3 + u32(key, 28) + (f ushr 32)
        val o3 = f and 0xffffffffL
        val out = ByteArray(16)
        for ((i, v) in longArrayOf(o0, o1, o2, o3).withIndex()) for (j in 0 until 4) out[i * 4 + j] = (v ushr (8 * j)).toByte()
        return out
    }

    private fun aeadTag(key: ByteArray, nonce: ByteArray, ad: ByteArray, ct: ByteArray, ctLen: Int): ByteArray {
        val polyKey = ByteArray(64).also { chachaBlock(key, 0, nonce, it) }.copyOf(32)
        fun pad(n: Int) = (16 - n % 16) % 16
        val mac = ByteArray(ad.size + pad(ad.size) + ctLen + pad(ctLen) + 16)
        ad.copyInto(mac, 0)
        var p = ad.size + pad(ad.size)
        ct.copyInto(mac, p, 0, ctLen)
        p += ctLen + pad(ctLen)
        for (i in 0 until 8) mac[p + i] = (ad.size.toLong() ushr (8 * i)).toByte()
        for (i in 0 until 8) mac[p + 8 + i] = (ctLen.toLong() ushr (8 * i)).toByte()
        return poly1305(polyKey, mac)
    }

    fun aeadEncrypt(key: ByteArray, nonce: ByteArray, ad: ByteArray, plaintext: ByteArray): ByteArray {
        val ct = chacha20(key, 1, nonce, plaintext)
        return ct + aeadTag(key, nonce, ad, ct, ct.size)
    }

    fun aeadDecrypt(key: ByteArray, nonce: ByteArray, ad: ByteArray, ciphertext: ByteArray): ByteArray {
        if (ciphertext.size < 16) throw CryptoException("ciphertext too short")
        val ctLen = ciphertext.size - 16
        val expected = aeadTag(key, nonce, ad, ciphertext, ctLen)
        var diff = 0
        for (i in 0 until 16) diff = diff or (expected[i].toInt() xor ciphertext[ctLen + i].toInt())
        if (diff != 0) throw CryptoException("authentication failed")
        return chacha20(key, 1, nonce, ciphertext.copyOf(ctLen))
    }

    // ---------------------------------------------------------------- X25519 (TweetNaCl)

    private fun gf() = LongArray(16)
    private val G121665 = LongArray(16).also { it[0] = 0xDB41; it[1] = 1 }

    private fun car(o: LongArray) {
        for (i in 0 until 16) {
            o[i] += 1L shl 16
            val c = o[i] shr 16
            if (i < 15) o[i + 1] += c - 1 else o[0] += 38 * (c - 1)
            o[i] -= c shl 16
        }
    }

    private fun sel(p: LongArray, q: LongArray, b: Int) {
        val c = (b - 1).toLong().inv()
        for (i in 0 until 16) {
            val t = c and (p[i] xor q[i])
            p[i] = p[i] xor t
            q[i] = q[i] xor t
        }
    }

    private fun pack(o: ByteArray, n: LongArray) {
        val m = gf()
        val t = n.copyOf()
        car(t); car(t); car(t)
        repeat(2) {
            m[0] = t[0] - 0xffed
            for (i in 1 until 15) {
                m[i] = t[i] - 0xffff - ((m[i - 1] shr 16) and 1)
                m[i - 1] = m[i - 1] and 0xffff
            }
            m[15] = t[15] - 0x7fff - ((m[14] shr 16) and 1)
            val b = ((m[15] shr 16) and 1).toInt()
            m[14] = m[14] and 0xffff
            sel(t, m, 1 - b)
        }
        for (i in 0 until 16) {
            o[2 * i] = (t[i] and 0xff).toByte()
            o[2 * i + 1] = (t[i] shr 8).toByte()
        }
    }

    private fun unpack(o: LongArray, n: ByteArray) {
        for (i in 0 until 16) o[i] = (n[2 * i].toLong() and 0xff) + ((n[2 * i + 1].toLong() and 0xff) shl 8)
        o[15] = o[15] and 0x7fff
    }

    private fun add(o: LongArray, a: LongArray, b: LongArray) { for (i in 0 until 16) o[i] = a[i] + b[i] }
    private fun sub(o: LongArray, a: LongArray, b: LongArray) { for (i in 0 until 16) o[i] = a[i] - b[i] }

    private fun mul(o: LongArray, a: LongArray, b: LongArray) {
        val t = LongArray(31)
        for (i in 0 until 16) for (j in 0 until 16) t[i + j] += a[i] * b[j]
        for (i in 0 until 15) t[i] += 38 * t[i + 16]
        for (i in 0 until 16) o[i] = t[i]
        car(o); car(o)
    }

    private fun sqr(o: LongArray, a: LongArray) = mul(o, a, a)

    private fun inv(o: LongArray, i: LongArray) {
        val c = i.copyOf()
        for (a in 253 downTo 0) {
            sqr(c, c)
            if (a != 2 && a != 4) mul(c, c, i)
        }
        c.copyInto(o)
    }

    private fun scalarMult(n: ByteArray, p: ByteArray): ByteArray {
        require(n.size == 32 && p.size == 32)
        val z = n.copyOf()
        z[31] = ((n[31].toInt() and 127) or 64).toByte()
        z[0] = (z[0].toInt() and 248).toByte()
        val x = gf(); unpack(x, p)
        val a = gf(); val b = x.copyOf(); val c = gf(); val d = gf(); val e = gf(); val f = gf()
        a[0] = 1; d[0] = 1
        for (i in 254 downTo 0) {
            val r = ((z[i ushr 3].toInt() and 0xff) ushr (i and 7)) and 1
            sel(a, b, r); sel(c, d, r)
            add(e, a, c); sub(a, a, c); add(c, b, d); sub(b, b, d)
            sqr(d, e); sqr(f, a); mul(a, c, a); mul(c, b, e)
            add(e, a, c); sub(a, a, c); sqr(b, a); sub(c, d, f)
            mul(a, c, G121665); add(a, a, d); mul(c, c, a); mul(a, d, f)
            mul(d, b, x); sqr(b, e)
            sel(a, b, r); sel(c, d, r)
        }
        inv(c, c)
        mul(a, a, c)
        return ByteArray(32).also { pack(it, a) }
    }

    private val BASE = ByteArray(32).also { it[0] = 9 }

    fun x25519Base(privateKey: ByteArray): ByteArray = scalarMult(privateKey, BASE)

    fun x25519(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        val out = scalarMult(privateKey, publicKey)
        var acc = 0
        for (v in out) acc = acc or v.toInt()
        if (acc == 0) throw CryptoException("key agreement failed")
        return out
    }
}
