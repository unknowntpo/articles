package io.example;

import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.Consumed;

/**
 * After 4.2.0: Clean topology with zero manual DLQ code.
 *
 * <p>Deserialization and processing errors are handled transparently by the framework:
 * <ol>
 *   <li>ClickEventSerde.deserializer() throws for malformed JSON.</li>
 *   <li>LogAndContinueExceptionHandler.handleError() is called automatically.</li>
 *   <li>The mapValues() business validation throws for negative count.</li>
 *   <li>LogAndContinueProcessingExceptionHandler.handleError() is called automatically.</li>
 *   <li>The handler builds a DLQ ProducerRecord and returns Response.resume(dlqRecords).</li>
 *   <li>RecordCollectorImpl sends the DLQ record via the SAME StreamsProducer →
 *       SAME Kafka transaction → tx-safe with exactly_once_v2.</li>
 * </ol>
 *
 * <p>DLQ headers added automatically by ExceptionHandlerUtils:
 * <ul>
 *   <li>__streams.errors.exception</li>
 *   <li>__streams.errors.stacktrace</li>
 *   <li>__streams.errors.message</li>
 *   <li>__streams.errors.topic</li>
 *   <li>__streams.errors.partition</li>
 *   <li>__streams.errors.offset</li>
 * </ul>
 *
 * <p>No DLQ-related code here. All error routing is configured in App.java via
 * {@code StreamsConfig.ERRORS_DEAD_LETTER_QUEUE_TOPIC_NAME_CONFIG}.
 */
public class ClickEventTopology {

    public Topology build(String inputTopic, String outputTopic) {
        StreamsBuilder builder = new StreamsBuilder();

        builder
            .stream(inputTopic, Consumed.with(Serdes.String(), new ClickEventSerde()))
            .mapValues(event -> {
                if (event.count < 0) {
                    throw new IllegalArgumentException("count must be non-negative");
                }
                return "user clicked ad=" + event.adId + " count=" + event.count;
            })
            .to(outputTopic);

        return builder.build();
    }
}
