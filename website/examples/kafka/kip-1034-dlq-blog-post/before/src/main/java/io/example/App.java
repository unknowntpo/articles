package io.example;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsConfig;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * Before 4.2.0: Manual DLQ demo using Kafka Streams 3.9.x.
 *
 * <p>Startup sequence:
 * <ol>
 *   <li>Admin client deletes existing topics and recreates them (clean slate).</li>
 *   <li>Producer sends a mix of valid, malformed, and business-invalid JSON records.</li>
 *   <li>Kafka Streams routes deserialization errors via ManualDlqHandler and
 *       processing errors via ClickEventManualDlqTopology (both manual DLQ paths are NOT tx-safe).</li>
 * </ol>
 *
 * <p>Verify DLQ after run:
 * <pre>
 *   kafka-console-consumer.sh --bootstrap-server localhost:9092 \
 *     --topic click-events-dlq --from-beginning \
 *     --property print.headers=true
 * </pre>
 */
public class App {

    private static final String BOOTSTRAP_SERVERS = "localhost:9092";
    private static final String INPUT_TOPIC       = "click-events";
    private static final String OUTPUT_TOPIC      = "click-events-output";
    private static final String DLQ_TOPIC         = "click-events-dlq";
    private static final String APP_ID            = "click-events-app-before-v1";

    public static void main(String[] args) throws Exception {
        // Step 1: clean state
        recreateTopics();

        // Step 2: send test data
        sendTestData();

        // Step 3: start streams
        KafkaStreams streams = buildStreams();
        streams.start();

        Runtime.getRuntime().addShutdownHook(new Thread(streams::close));

        System.out.println("[App] Streams started. Press Ctrl-C to stop.");
        System.out.println("[App] Check DLQ with:");
        System.out.println("  kafka-console-consumer.sh --bootstrap-server localhost:9092 \\");
        System.out.println("    --topic " + DLQ_TOPIC + " --from-beginning --property print.headers=true");
        Thread.currentThread().join();
    }

    // ── topic management ────────────────────────────────────────────────────

    private static void recreateTopics() throws ExecutionException, InterruptedException {
        Properties adminProps = new Properties();
        adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);

        try (AdminClient admin = AdminClient.create(adminProps)) {
            List<String> topics = Arrays.asList(INPUT_TOPIC, OUTPUT_TOPIC, DLQ_TOPIC);

            // delete existing topics (ignore errors if they don't exist)
            Set<String> existing = admin.listTopics().names().get();
            List<String> toDelete = topics.stream()
                    .filter(existing::contains)
                    .collect(java.util.stream.Collectors.toList());
            if (!toDelete.isEmpty()) {
                admin.deleteTopics(toDelete).all().get();
                System.out.println("[App] Deleted topics: " + toDelete);
                waitForTopicsAbsent(admin, toDelete);
            }

            Collection<NewTopic> newTopics = Arrays.asList(
                    new NewTopic(INPUT_TOPIC,  1, (short) 1),
                    new NewTopic(OUTPUT_TOPIC, 1, (short) 1),
                    new NewTopic(DLQ_TOPIC,    1, (short) 1)
            );
            admin.createTopics(newTopics).all().get();
            System.out.println("[App] Created topics: " + topics);
            waitForTopicsPresent(admin, topics);
        }
    }

    private static void waitForTopicsAbsent(AdminClient admin, List<String> topics)
            throws ExecutionException, InterruptedException {
        waitForTopicState(admin, topics, false);
    }

    private static void waitForTopicsPresent(AdminClient admin, List<String> topics)
            throws ExecutionException, InterruptedException {
        waitForTopicState(admin, topics, true);
    }

    private static void waitForTopicState(AdminClient admin, List<String> topics, boolean shouldExist)
            throws ExecutionException, InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            Set<String> existing = admin.listTopics().names().get();
            boolean matches = shouldExist
                    ? topics.stream().allMatch(existing::contains)
                    : topics.stream().noneMatch(existing::contains);
            if (matches) {
                return;
            }
            Thread.sleep(250);
        }
        throw new ExecutionException(new TimeoutException(
                "Timed out waiting for topics to " + (shouldExist ? "exist" : "disappear") + ": " + topics));
    }

    // ── test data ────────────────────────────────────────────────────────────

    private static void sendTestData() throws ExecutionException, InterruptedException {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            List<String[]> messages = Arrays.asList(
                    // valid records
                    new String[]{"user-1", "{\"ad_id\":\"banner-A\",\"count\":3}"},
                    new String[]{"user-2", "{\"ad_id\":\"video-B\",\"count\":1}"},
                    // malformed JSON — ClickEventSerde fails before topology -> ManualDlqHandler
                    new String[]{"user-3", "NOT_VALID_JSON"},
                    new String[]{"user-4", "{broken json"},
                    // valid JSON but invalid business input — topology catches and routes to DLQ
                    new String[]{"user-5", "{\"ad_id\":\"sidebar-C\",\"count\":-7}"},
                    // another valid record
                    new String[]{"user-6", "{\"ad_id\":\"sidebar-D\",\"count\":7}"}
            );

            for (String[] msg : messages) {
                producer.send(new ProducerRecord<>(INPUT_TOPIC, msg[0], msg[1])).get();
                System.out.println("[App] Sent: key=" + msg[0] + " value=" + msg[1]);
            }
        }
        System.out.println("[App] Test data sent.");
    }

    // ── streams ──────────────────────────────────────────────────────────────

    private static KafkaStreams buildStreams() {
        Properties streamsProps = new Properties();
        streamsProps.put(StreamsConfig.APPLICATION_ID_CONFIG,  APP_ID);
        streamsProps.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        streamsProps.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG,
                org.apache.kafka.common.serialization.Serdes.String().getClass());
        // In 3.9.x deserialization errors still require a manual handler to publish DLQ records.
        streamsProps.put(StreamsConfig.DEFAULT_DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG,
                ManualDlqHandler.class);
        streamsProps.put("dlq.topic.name", DLQ_TOPIC);

        // Stand-alone DLQ producer used by ClickEventManualDlqTopology (NOT tx-safe)
        Map<String, Object> dlqProducerProps = new HashMap<>();
        dlqProducerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        dlqProducerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        dlqProducerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        KafkaProducer<String, String> dlqProducer = new KafkaProducer<>(dlqProducerProps);

        ClickEventManualDlqTopology topology =
                new ClickEventManualDlqTopology(record -> dlqProducer.send(record).get(), DLQ_TOPIC);
        return new KafkaStreams(topology.build(INPUT_TOPIC, OUTPUT_TOPIC), streamsProps);
    }
}
