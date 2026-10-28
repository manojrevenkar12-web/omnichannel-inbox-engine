package inbox.service

import java.security.MessageDigest
import java.util.UUID

object Ids {
    fun new(prefix: String): String = prefix + "_" + UUID.randomUUID().toString().replace("-", "").take(20)

    fun sha256(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Digits of a phone number, so a WhatsApp id (491701234567) and an SMS sender (+49 170 1234567) match. */
    fun phoneDigits(address: String): String = address.filter { it.isDigit() }
}
