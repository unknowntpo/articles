package io.example;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.streams.errors.DeserializationExceptionHandler;
import org.apache.kafka.streams.errors.ErrorHandlerContext;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ManualDlqHandlerTest {

    @Test
    void testHandleInvalidRecordRoutesToDlqAndReturnsContinue() {
        RecordingRawDlqSender sender = new RecordingRawDlqSender();
        ManualDlqHandler handler = new ManualDlqHandler(sender);

        Map<String, Object> configs = new HashMap<>();
        configs.put("dlq.topic.name", "click-events-dlq");
        configs.put("bootstrap.servers", "dummy:9092");
        handler.configure(configs);

        ConsumerRecord<byte[], byte[]> record = new ConsumerRecord<>(
                "click-events",
                0,
                12L,
                "user-bad".getBytes(StandardCharsets.UTF_8),
                "NOT_VALID_JSON".getBytes(StandardCharsets.UTF_8)
        );

        DeserializationExceptionHandler.DeserializationHandlerResponse response =
                handler.handle((ErrorHandlerContext) null, record, new RuntimeException("bad json"));

        assertEquals(DeserializationExceptionHandler.DeserializationHandlerResponse.CONTINUE, response);
        assertEquals(1, sender.records.size());

        ProducerRecord<byte[], byte[]> dlqRecord = sender.records.get(0);
        assertEquals("click-events-dlq", dlqRecord.topic());
        assertArrayEquals("user-bad".getBytes(StandardCharsets.UTF_8), dlqRecord.key());
        assertArrayEquals("NOT_VALID_JSON".getBytes(StandardCharsets.UTF_8), dlqRecord.value());
        assertNotNull(dlqRecord.headers().lastHeader("__manual.error.topic"));
        assertNotNull(dlqRecord.headers().lastHeader("__manual.error.partition"));
        assertNotNull(dlqRecord.headers().lastHeader("__manual.error.offset"));
        assertNotNull(dlqRecord.headers().lastHeader("__manual.error.message"));
        assertNotNull(dlqRecord.headers().lastHeader("__manual.error.class"));
    }

    private static class RecordingRawDlqSender implements RawDlqSender {
        private final List<ProducerRecord<byte[], byte[]>> records = new ArrayList<>();

        @Override
        public void send(ProducerRecord<byte[], byte[]> record) {
            records.add(record);
        }
    }
}
