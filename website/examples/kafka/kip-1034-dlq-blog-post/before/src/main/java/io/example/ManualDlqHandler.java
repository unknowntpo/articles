package io.example;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.streams.errors.DeserializationExceptionHandler;
import org.apache.kafka.streams.errors.ErrorHandlerContext;

import java.util.HashMap;
import java.util.Map;

/**
 * Step 3: DeserializationExceptionHandler that manually sends failed records to a DLQ.
 *
 * <p>This handler is called when Kafka Streams cannot deserialize a record using the
 * configured Serde (e.g., a ClickEventSerde that throws on malformed JSON).
 * The processor has not run yet — context.forward() is not available here.
 *
 * <p>To route to DLQ, we must create our own KafkaProducer and call producer.send()
 * directly. This is the ONLY option in Kafka Streams 3.x.
 *
 * <p>⚠️ NOT TX-SAFE:
 * <pre>
 *   1. This producer is NOT part of Streams' internal transaction.
 *   2. Streams uses processing.guarantee=exactly_once_v2:
 *      - Streams calls producer.beginTransaction() internally.
 *      - This handler's producer.send() goes to a DIFFERENT transaction.
 *      - If Streams' transaction is aborted (crash/rebalance), our DLQ send is already committed.
 *      - On retry, the same record is sent to DLQ again → DUPLICATE DLQ entries.
 *   3. To make it tx-safe you would need to:
 *      a. Get a reference to Streams' internal StreamsProducer (private API).
 *      b. Call sendOffsetsToTransaction() in sync with Streams' commit cycle (impossible externally).
 *      → Effectively impossible without forking Kafka Streams.
 * </pre>
 *
 * <p>In Kafka Streams 4.2.0 (KIP-1034) this entire handler is replaced by one config line:
 * {@code props.put(StreamsConfig.ERRORS_DEAD_LETTER_QUEUE_TOPIC_NAME_CONFIG, "click-events-dlq")}
 * The framework sends to DLQ via RecordCollectorImpl (same transaction) automatically.
 */
public class ManualDlqHandler implements DeserializationExceptionHandler {

    private static final String DLQ_TOPIC_CONFIG_KEY = "dlq.topic.name";
    private static final String BOOTSTRAP_SERVERS_KEY = "bootstrap.servers";

    private final RawDlqSender injectedSender;
    private KafkaProducer<byte[], byte[]> dlqProducer;
    private String dlqTopic;

    public ManualDlqHandler() {
        this(null);
    }

    ManualDlqHandler(RawDlqSender injectedSender) {
        this.injectedSender = injectedSender;
    }

    @Override
    public void configure(Map<String, ?> configs) {
        this.dlqTopic = (String) configs.get(DLQ_TOPIC_CONFIG_KEY);

        if (injectedSender == null) {
            Map<String, Object> producerProps = new HashMap<>();
            producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, configs.get(BOOTSTRAP_SERVERS_KEY));
            producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
            producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
            // NOTE: we cannot use transactional.id here because we don't have access to Streams' tx
            this.dlqProducer = new KafkaProducer<>(producerProps);
        }

        System.out.println("[ManualDlqHandler] Configured with dlqTopic=" + dlqTopic);
    }

    @Override
    public DeserializationHandlerResponse handle(
            ErrorHandlerContext context,
            ConsumerRecord<byte[], byte[]> record,
            Exception exception) {

        System.err.printf("[ManualDlqHandler] Deserialization error on topic=%s partition=%d offset=%d: %s%n",
                record.topic(), record.partition(), record.offset(), exception.getMessage());

        sendToDlq(record, exception);

        // Return CONTINUE so Streams skips this record and processes the next one.
        // If we returned FAIL, the Streams application would shut down.
        return DeserializationHandlerResponse.CONTINUE;
    }

    /**
     * ⚠️ This send is NOT in Streams' Kafka transaction.
     * See class-level Javadoc for the full problem description.
     */
    private void sendToDlq(ConsumerRecord<byte[], byte[]> record, Exception cause) {
        if (dlqTopic == null) {
            System.err.println("[ManualDlqHandler] dlq.topic.name not configured, dropping record");
            return;
        }

        ProducerRecord<byte[], byte[]> dlqRecord =
                new ProducerRecord<>(dlqTopic, record.key(), record.value());

        // Manually copy original headers and add error metadata
        record.headers().forEach(h -> dlqRecord.headers().add(h));
        dlqRecord.headers().add("__manual.error.topic", record.topic().getBytes());
        dlqRecord.headers().add("__manual.error.partition",
                String.valueOf(record.partition()).getBytes());
        dlqRecord.headers().add("__manual.error.offset",
                String.valueOf(record.offset()).getBytes());
        dlqRecord.headers().add("__manual.error.message",
                (cause.getMessage() != null ? cause.getMessage() : "null").getBytes());
        dlqRecord.headers().add("__manual.error.class",
                cause.getClass().getName().getBytes());

        try {
            if (injectedSender != null) {
                injectedSender.send(dlqRecord);
            } else {
                dlqProducer.send(dlqRecord).get();
            }
            System.out.printf("[ManualDlqHandler] Sent to DLQ (NOT tx-safe): topic=%s offset=%d%n",
                    record.topic(), record.offset());
        } catch (Exception e) {
            System.err.println("[ManualDlqHandler] Failed to send to DLQ: " + e.getMessage());
        }
    }
}
