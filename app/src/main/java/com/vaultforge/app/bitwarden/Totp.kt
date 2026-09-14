package com.vaultforge.app.bitwarden

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLDecoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 标准 TOTP 生成器（RFC 6238），完全本地计算、不联网。
 * 支持两种来源格式：明文 Base32 密钥 / otpauth:// URI（secret、period、digits、algorithm 参数）。
 * Steam Guard 特殊码暂不支持（返回 null，由 UI 友好提示）。
 */
object Totp {

    data class Config(
        val secret: ByteArray,
        val digits: Int = 6,
        val period: Int = 30,
        val algorithm: String = "SHA1",
        val issuer: String = "",
        val account: String = "",
        val steam: Boolean = false,
    )

    /** 解析密钥描述；无法识别时返回 null。 */
    fun parse(raw: String): Config? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        return try {
            when {
                s.startsWith("otpauth://", true) || s.startsWith("steam://", true) -> parseUri(s)
                else -> base32(s)?.let { Config(it) }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseUri(s: String): Config? {
        val uri = URI(s)
        val scheme = (uri.scheme ?: "").lowercase()
        val host = (uri.host ?: "").lowercase()
        val q = parseQuery(uri.rawQuery)
        val secret = q["secret"]?.let { base32(it) } ?: return null
        val steam = scheme == "steam" || host == "steam"
        return Config(
            secret = secret,
            digits = q["digits"]?.toIntOrNull() ?: if (steam) 5 else 6,
            period = q["period"]?.toIntOrNull() ?: 30,
            algorithm = (q["algorithm"] ?: "SHA1").uppercase(),
            issuer = q["issuer"] ?: "",
            account = (uri.path ?: "").removePrefix("/"),
            steam = steam,
        )
    }

    /** 生成当前时间窗的动态码；不支持的格式（如 Steam）返回 null。 */
    fun code(cfg: Config, atMillis: Long): String? {
        if (cfg.steam) return null
        val counter = atMillis / 1000L / cfg.period
        val msg = ByteArray(8)
        var c = counter
        for (i in 7 downTo 0) {
            msg[i] = (c and 0xFF).toByte()
            c = c shr 8
        }
        val mac = Mac.getInstance(
            when (cfg.algorithm) {
                "SHA256" -> "HmacSHA256"
                "SHA512" -> "HmacSHA512"
                else -> "HmacSHA1"
            }
        )
        mac.init(SecretKeySpec(cfg.secret, "HmacSHA1"))
        val h = mac.doFinal(msg)
        val off = h[h.size - 1].toInt() and 0x0F
        val bin = ((h[off].toInt() and 0x7F) shl 24) or
            ((h[off + 1].toInt() and 0xFF) shl 16) or
            ((h[off + 2].toInt() and 0xFF) shl 8) or
            (h[off + 3].toInt() and 0xFF)
        val mod = when (cfg.digits) {
            7 -> 10_000_000
            8 -> 100_000_000
            else -> 1_000_000
        }
        return (bin % mod).toString().padStart(cfg.digits, '0')
    }

    /** 当前时间窗剩余秒数（用于倒计时展示）。 */
    fun secondsLeft(cfg: Config, atMillis: Long): Int {
        val left = cfg.period - ((atMillis / 1000L) % cfg.period).toInt()
        return if (left <= 0) cfg.period else left
    }

    private fun base32(s: String): ByteArray? {
        val clean = s.replace(" ", "").replace("=", "").uppercase()
        if (clean.isEmpty()) return null
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val out = ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (ch in clean) {
            val v = alphabet.indexOf(ch)
            if (v < 0) return null
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    private fun parseQuery(raw: String?): Map<String, String> {
        if (raw.isNullOrEmpty()) return emptyMap()
        val map = HashMap<String, String>()
        for (kv in raw.split("&")) {
            val i = kv.indexOf('=')
            if (i <= 0) continue
            val k = kv.substring(0, i)
            val v = kv.substring(i + 1)
            map[k] = try { URLDecoder.decode(v, "UTF-8") } catch (e: Exception) { v }
        }
        return map
    }
}