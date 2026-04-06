package io.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.KStream;

import java.util.Collections;

/**
 * Step 1 + Step 2: Manual DLQ approach using flatMap + try-catch.
 *
 * <p>Pain points shown here:
 * <ol>
 *   <li>We need a separate KafkaProducer to write to DLQ — it is outside Streams' transaction.</li>
 *   <li>If Streams aborts after dlqProducer.send(), the DLQ record is already committed
 *       → duplicate DLQ writes on retry.</li>
 *   <li>No automatic error-metadata headers on DLQ records; we must add them manually.</li>
 *   <li>For Deserialization errors this approach does not even apply — see ManualDlqHandler.</li>
 * </ol>
 *
 * <p>Context.forward() alternative (Step 2 — tx-safe for processing errors only):
 * Instead of calling dlqProducer.send() inside flatMap, use a Processor/Transformer and
 * context.forward() to a DLQ sink defined in the topology. That routes through
 * RecordCollectorImpl (same StreamsProducer → same tx). But:
 * <ul>
 *   <li>You must hard-code a DLQ sink in the topology for every error path.</li>
 *   <li>Still cannot handle Deserialization errors (they happen before any processor runs).</li>
 * </ul>
 */
public class ClickEventManualDlqTopology {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final KafkaProducer<String, String> dlqProducer;
    private final String dlqTopic;

    public ClickEventManualDlqTopology(KafkaProducer<String, String> dlqProducer, String dlqTopic) {
        this.dlqProducer = dlqProducer;
        this.dlqTopic = dlqTopic;
    }

    public Topology build(String inputTopic, String outputTopic) {
        StreamsBuilder builder = new StreamsBuilder();

        KStream<String, String> stream = builder.stream(inputTopic);

        // flatMap: try to parse JSON; on failure, manually send to DLQ (NOT tx-safe)
        stream
            .flatMap((key, value) -> {
                try {
                    ClickEvent event = MAPPER.readValue(value, ClickEvent.class);
                    String processed = "user=" + key + " clicked ad=" + event.adId + " count=" + event.count;
                    return Collections.singletonList(KeyValue.pair(key, processed));
                } catch (Exception e) {
                    System.err.println("[ManualDlq] Parse error for key=" + key + ": " + e.getMessage());
                    sendToDlq(key, value, e);
                    return Collections.emptyList();
                }
            })
            .to(outputTopic);

        return builder.build();
    }

    /**
     * Sends the bad record to DLQ using a standalone producer.
     *
     * <p>⚠️ NOT TX-SAFE: If Kafka Streams is configured with processing.guarantee=exactly_once_v2
     * and the transaction is aborted after this send, the DLQ record has already been flushed to
     * Kafka. On retry, the same record will be sent to DLQ again → duplicate DLQ entries.
     * Making this tx-safe would require sharing Streams' internal StreamsProducer,
     * which is private API — effectively impossible without forking Kafka.
     */
    private void sendToDlq(String key, String value, Exception cause) {
        ProducerRecord<String, String> dlqRecord = new ProducerRecord<>(dlqTopic, key, value);
        // Add error metadata headers manually (in 4.2.0 these are added automatically)
        dlqRecord.headers().add("error.message", cause.getMessage() != null
                ? cause.getMessage().getBytes() : "null".getBytes());
        dlqRecord.headers().add("error.class", cause.getClass().getName().getBytes());
        try {
            dlqProducer.send(dlqRecord).get(); // synchronous to highlight ordering concern
            System.out.println("[ManualDlq] Sent to DLQ (NOT tx-safe): key=" + key);
        } catch (Exception ex) {
            System.err.println("[ManualDlq] Failed to send to DLQ: " + ex.getMessage());
        }
    }
}
