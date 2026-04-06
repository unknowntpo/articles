package io.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serializer;

import java.io.IOException;

/**
 * A Serde for ClickEvent that throws on malformed JSON.
 *
 * <p>When Kafka Streams calls deserialize() and gets an exception, it invokes
 * the configured DeserializationExceptionHandler (handleError in 4.2.0).
 * LogAndContinueExceptionHandler will then route the bad record to the DLQ topic
 * configured via StreamsConfig.ERRORS_DEAD_LETTER_QUEUE_TOPIC_NAME_CONFIG,
 * all within the same Kafka transaction.
 */
public class ClickEventSerde implements Serde<ClickEvent> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public Serializer<ClickEvent> serializer() {
        return (topic, data) -> {
            if (data == null) return null;
            try {
                return MAPPER.writeValueAsBytes(data);
            } catch (IOException e) {
                throw new RuntimeException("Failed to serialize ClickEvent", e);
            }
        };
    }

    @Override
    public Deserializer<ClickEvent> deserializer() {
        return (topic, data) -> {
            if (data == null) return null;
            try {
                return MAPPER.readValue(data, ClickEvent.class);
            } catch (IOException e) {
                // This exception propagates to DeserializationExceptionHandler.handleError()
                // In 4.2.0: LogAndContinueExceptionHandler sends the bad record to the DLQ topic
                // In 3.9.x: LogAndContinueExceptionHandler just logs and skips — no DLQ
                throw new RuntimeException("Failed to deserialize ClickEvent from: " + new String(data), e);
            }
        };
    }
}
