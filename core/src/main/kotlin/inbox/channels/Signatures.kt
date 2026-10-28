package inbox.channels

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Webhook signature checks. Comparisons are constant-time. */
object Signatures {
    private fun hmac(alg: String, secret: String, data: ByteArray): ByteArray =
        Mac.getInstance(alg).run {
            init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), alg))
            doFinal(data)
        }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** Meta (WhatsApp, Instagram): `X-Hub-Signature-256: sha256=<hex HMAC-SHA256 of the raw body>`. */
    fun metaSignature(appSecret: String, rawBody: ByteArray): String = "sha256=" + hex(hmac("HmacSHA256", appSecret, rawBody))

    fun verifyMeta(appSecret: String, rawBody: ByteArray, header: String?): Boolean {
        if (header.isNullOrBlank()) return false
        return MessageDigest.isEqual(metaSignature(appSecret, rawBody).toByteArray(), header.trim().toByteArray())
    }

    /**
     * Twilio-style SMS: base64 HMAC-SHA1 over the full callback URL followed by every form
     * parameter name and value, sorted by name.
     */
    fun smsSignature(authToken: String, url: String, params: Map<String, String>): String {
        val data = buildString {
            append(url)
            params.toSortedMap().forEach { (k, v) -> append(k).append(v) }
        }
        return Base64.getEncoder().encodeToString(hmac("HmacSHA1", authToken, data.toByteArray(Charsets.UTF_8)))
    }

    fun verifySms(authToken: String, url: String, params: Map<String, String>, header: String?): Boolean {
        if (header.isNullOrBlank()) return false
        return MessageDigest.isEqual(smsSignature(authToken, url, params).toByteArray(), header.trim().toByteArray())
    }
}
