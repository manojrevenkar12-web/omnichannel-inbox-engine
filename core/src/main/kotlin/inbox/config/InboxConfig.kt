package inbox.config

import io.smallrye.config.ConfigMapping
import java.time.Duration
import java.time.Instant

/** Every setting the inbox reads; values live in application.properties, none in code. */
@ConfigMapping(prefix = "inbox")
interface InboxConfig {
    fun whatsapp(): WhatsApp

    fun instagram(): Instagram

    fun sms(): Sms

    fun email(): Email

    fun sender(): Sender

    fun agent(): Agent

    fun workers(): Workers

    fun demo(): Demo

    interface WhatsApp {
        fun appSecret(): String

        fun verifyToken(): String

        fun graphUrl(): String

        fun apiVersion(): String

        fun accessToken(): String

        fun serviceBilledFrom(): Instant
    }

    interface Instagram {
        fun appSecret(): String
    }

    interface Sms {
        fun authToken(): String

        fun publicUrl(): String

        fun apiUrl(): String

        fun accountSid(): String
    }

    interface Email {
        fun topicArn(): String

        fun secret(): String
    }

    interface Sender {
        fun pairSpacing(): Duration

        fun requestTimeout(): Duration

        fun ambiguousWait(): Duration

        fun maxAttempts(): Int

        fun instance(): Int

        fun instances(): Int

        fun batch(): Int
    }

    interface Agent {
        fun url(): String

        fun quiet(): Duration

        fun minConfidence(): Double

        fun maxReplies(): Int

        fun timeout(): Duration
    }

    interface Workers {
        fun enabled(): Boolean
    }

    interface Demo {
        fun seed(): Boolean
    }
}
