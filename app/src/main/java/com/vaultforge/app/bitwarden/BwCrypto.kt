package com.vaultforge.app.bitwarden

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Bitwarden 协议加密原语（与 VaultForge Server / bwctl 的 Go 实现逐字节一致）。
 *
 * masterKey          = PBKDF2-SHA256(password, email, iters) 或 Argon2id(password, SHA256(email))
 * masterPasswordHash = PBKDF2-SHA256(masterKey, password, 1)
 * stretched          = HKDF-Expand(masterKey, "enc", 32) || HKDF-Expand(masterKey, "mac", 32)
 * EncString          = "2.<iv>|<ct>|<mac>" (AES-256-CBC + HMAC-SHA256, PKCS7)
 */
object BwCrypto {

    fun deriveMasterKey(
        password: String,
        email: String,
        kdf: Int,
        iterations: Int,
        memory: Int?,
        parallelism: Int?,
    ): ByteArray {
        val em = email.trim().lowercase()
        return when (kdf) {
            1 -> {
                val salt = MessageDigest.getInstance("SHA-256").digest(em.toByteArray(Charsets.UTF_8))
                argon2id(
                    password.toByteArray(Charsets.UTF_8),
                    salt,
                    if (iterations <= 0) 3 else iterations,
                    if (memory == null || memory <= 0) 64 else memory,
                    if (parallelism == null || parallelism <= 0) 4 else parallelism,
                )
            }
            else -> pbkdf2(
                password.toByteArray(Charsets.UTF_8),
                em.toByteArray(Charsets.UTF_8),
                if (iterations <= 0) 600000 else iterations,
                32,
            )
        }
    }

    fun masterPasswordHash(masterKey: ByteArray, password: String): ByteArray =
        pbkdf2(masterKey, password.toByteArray(Charsets.UTF_8), 1, 32)

    fun stretchKey(masterKey: ByteArray): Pair<ByteArray, ByteArray> =
        hkdfExpand(masterKey, "enc", 32) to hkdfExpand(masterKey, "mac", 32)

    /** PBKDF2-HMAC-SHA256 纯 Kotlin 实现（避免 JCE PBEKeySpec 的字符编码差异）。 */
    fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int, dkLen: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        val hLen = 32
        val blocks = (dkLen + hLen - 1) / hLen
        val out = ByteArray(blocks * hLen)
        for (i in 1..blocks) {
            mac.update(salt)
            mac.update(
                byteArrayOf(
                    (i ushr 24).toByte(),
                    (i ushr 16).toByte(),
                    (i ushr 8).toByte(),
                    i.toByte(),
                )
            )
            var u = mac.doFinal()
            val t = u.copyOf()
            for (j in 2..iterations) {
                u = mac.doFinal(u)
                for (k in 0 until hLen) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
            }
            System.arraycopy(t, 0, out, (i - 1) * hLen, hLen)
        }
        return if (dkLen == out.size) out else out.copyOf(dkLen)
    }

    fun hkdfExpand(prk: ByteArray, info: String, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArrayOutputStream()
        var t = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            mac.update(t)
            mac.update(info.toByteArray(Charsets.UTF_8))
            mac.update(counter.toByte())
            t = mac.doFinal()
            out.write(t)
            counter++
        }
        val all = out.toByteArray()
        return if (all.size == length) all else all.copyOf(length)
    }

    /** 解密单个 EncString 字段；空串返回 null；非加密串按明文透传。 */
    fun decSeg(encKey: ByteArray, macKey: ByteArray, s: String): ByteArray? {
        if (s.isEmpty()) return null
        val dot = s.indexOf('.')
        if (dot <= 0) return s.toByteArray(Charsets.UTF_8)
        val type = s.substring(0, dot)
        val segs = s.substring(dot + 1).split("|")
        when (type) {
            "2" -> {
                if (segs.size != 3) throw BwException("字段段数异常")
                val iv = b64d(segs[0])
                val ct = b64d(segs[1])
                val mac = b64d(segs[2])
                val expect = hmacSha256(macKey, iv, ct)
                if (!MessageDigest.isEqual(expect, mac)) throw BwException("MAC 校验失败")
                return aesCbcDecrypt(encKey, iv, ct)
            }
            "0" -> {
                if (segs.size != 2) throw BwException("字段段数异常")
                return aesCbcDecrypt(encKey, b64d(segs[0]), b64d(segs[1]))
            }
            else -> throw BwException("暂不支持的加密类型：" + type)
        }
    }

    fun decString(encKey: ByteArray, macKey: ByteArray, s: String): String =
        decSeg(encKey, macKey, s)?.toString(Charsets.UTF_8) ?: ""

    fun b64d(s: String): ByteArray = Base64.getDecoder().decode(s)

    fun b64e(b: ByteArray): String = Base64.getEncoder().encodeToString(b)

    private fun hmacSha256(key: ByteArray, vararg chunks: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        chunks.forEach { mac.update(it) }
        return mac.doFinal()
    }

    private fun aesCbcDecrypt(key: ByteArray, iv: ByteArray, ct: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(ct)
    }

    private fun argon2id(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        memoryMb: Int,
        parallelism: Int,
    ): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(iterations)
            .withMemoryAsKB(memoryMb * 1024)
            .withParallelism(parallelism)
            .withSalt(salt)
            .build()
        val gen = Argon2BytesGenerator()
        gen.init(params)
        val out = ByteArray(32)
        gen.generateBytes(password, out)
        return out
    }
}