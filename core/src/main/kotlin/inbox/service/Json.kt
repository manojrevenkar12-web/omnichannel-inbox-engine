package inbox.service

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.quarkus.jackson.ObjectMapperCustomizer
import jakarta.inject.Singleton

/** One JSON configuration for the API, stored webhook facts and provider calls. */
@Singleton
class JsonCustomizer : ObjectMapperCustomizer {
    override fun customize(mapper: ObjectMapper) {
        configure(mapper)
    }

    companion object {
        fun configure(mapper: ObjectMapper): ObjectMapper =
            mapper
                .registerKotlinModule()
                .registerModule(JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

        fun standalone(): ObjectMapper = configure(ObjectMapper())
    }
}
