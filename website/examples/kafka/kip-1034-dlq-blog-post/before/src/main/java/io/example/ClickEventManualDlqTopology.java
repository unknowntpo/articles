package io.example;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Produced;

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
 *   <li>Deserialization errors must be handled separately via ManualDlqHandler.</li>
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

    private final DlqSender dlqSender;
    private final String dlqTopic;

    public ClickEventManualDlqTopology(DlqSender dlqSender, String dlqTopic) {
        this.dlqSender = dlqSender;
        this.dlqTopic = dlqTopic;
    }

    public Topology build(String inputTopic, String outputTopic) {
        StreamsBuilder builder = new StreamsBuilder();

        KStream<String, ClickEvent> stream =
                builder.stream(inputTopic, Consumed.with(Serdes.String(), new ClickEventSerde()));

        // flatMap: business validation fails after deserialize succeeds -> manual DLQ (NOT tx-safe)
        stream
            .flatMap((key, event) -> {
                try {
                    if (event.count < 0) {
                        throw new IllegalArgumentException("count must be non-negative");
                    }
                    String processed = "user=" + key + " clicked ad=" + event.adId + " count=" + event.count;
                    return Collections.singletonList(KeyValue.pair(key, processed));
                } catch (Exception e) {
                    System.err.println("[ManualDlq] Processing error for key=" + key + ": " + e.getMessage());
                    sendToDlq(key, event, e);
                    return Collections.emptyList();
                }
            })
            .to(outputTopic, Produced.with(Serdes.String(), Serdes.String()));

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
    private void sendToDlq(String key, ClickEvent value, Exception cause) {
        String payload;
        try {
            payload = MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            payload = String.valueOf(value);
        }

        ProducerRecord<String, String> dlqRecord = new ProducerRecord<>(dlqTopic, key, payload);
        // Add error metadata headers manually (in 4.2.0 these are added automatically)
        dlqRecord.headers().add("error.message", cause.getMessage() != null
                ? cause.getMessage().getBytes() : "null".getBytes());
        dlqRecord.headers().add("error.class", cause.getClass().getName().getBytes());
        try {
            dlqSender.send(dlqRecord);
            System.out.println("[ManualDlq] Sent to DLQ (NOT tx-safe): key=" + key);
        } catch (Exception ex) {
            System.err.println("[ManualDlq] Failed to send to DLQ: " + ex.getMessage());
        }
    }
}
