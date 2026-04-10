package io.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serializer;

import java.io.IOException;

/**
 * JSON serde used in the pre-KIP-1034 example so deserialization failures
 * actually happen before the record enters the topology.
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
                throw new RuntimeException("Failed to deserialize ClickEvent from: " + new String(data), e);
            }
        };
    }
}

