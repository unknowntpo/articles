package io.example;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StreamsConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
@Execution(ExecutionMode.SAME_THREAD)
class ManualDlqHandlerIntegrationTest {

    private static final String TOPIC_SUFFIX = "manual-dlq-handler-integration-test";
    private static final String INPUT_TOPIC = "it-click-events-" + TOPIC_SUFFIX;
    private static final String OUTPUT_TOPIC = "it-click-events-output-" + TOPIC_SUFFIX;
    private static final String DLQ_TOPIC = "it-click-events-dlq-" + TOPIC_SUFFIX;

    private static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"))
                    .withReuse(true);

    private KafkaStreams streams;

    @BeforeAll
    static void startKafka() {
        KAFKA.start();
        System.out.println("[ManualDlqHandlerIntegrationTest] bootstrap.servers=" + KAFKA.getBootstrapServers());
        System.out.println("[ManualDlqHandlerIntegrationTest] inputTopic=" + INPUT_TOPIC);
        System.out.println("[ManualDlqHandlerIntegrationTest] outputTopic=" + OUTPUT_TOPIC);
        System.out.println("[ManualDlqHandlerIntegrationTest] dlqTopic=" + DLQ_TOPIC);
        System.out.println("[ManualDlqHandlerIntegrationTest] reuse=true (requires ~/.testcontainers.properties to keep container after JVM exits)");
    }

    @BeforeEach
    void setUpTopics() throws Exception {
        Properties adminProps = new Properties();
        adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());

        try (AdminClient admin = AdminClient.create(adminProps)) {
            admin.deleteTopics(List.of(INPUT_TOPIC, OUTPUT_TOPIC, DLQ_TOPIC)).all().get();
        } catch (Exception ignored) {
            // Fresh run is fine; topic deletion is best-effort so the names can be reused safely.
        }

        try (AdminClient admin = AdminClient.create(adminProps)) {
            admin.createTopics(List.of(
                    new NewTopic(INPUT_TOPIC, 1, (short) 1),
                    new NewTopic(OUTPUT_TOPIC, 1, (short) 1),
                    new NewTopic(DLQ_TOPIC, 1, (short) 1)
            )).all().get();
        }
    }

    @AfterEach
    void tearDown() {
        if (streams != null) {
            streams.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void shouldSendMalformedRecordToDlqViaRealKafkaProducer() throws Exception {
        ClickEventManualDlqTopology topology =
                new ClickEventManualDlqTopology(record -> {
                    throw new AssertionError("processing-path DLQ sender should not be used in this test");
                }, DLQ_TOPIC);

        Properties streamsProps = new Properties();
        streamsProps.put(StreamsConfig.APPLICATION_ID_CONFIG, "manual-dlq-it-" + UUID.randomUUID());
        streamsProps.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        streamsProps.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass());
        streamsProps.put(StreamsConfig.DEFAULT_DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG,
                ManualDlqHandler.class);
        streamsProps.put(StreamsConfig.STATE_DIR_CONFIG,
                System.getProperty("java.io.tmpdir") + "/manual-dlq-it-" + UUID.randomUUID());
        streamsProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        streamsProps.put("dlq.topic.name", DLQ_TOPIC);

        streams = new KafkaStreams(topology.build(INPUT_TOPIC, OUTPUT_TOPIC), streamsProps);
        streams.start();
        waitForState(KafkaStreams.State.RUNNING);

        sendInvalidRecord("user-bad", "NOT_VALID_JSON");

        try (KafkaConsumer<byte[], byte[]> consumer = createDlqConsumer()) {
            ConsumerRecord<byte[], byte[]> dlqRecord = waitForSingleRecord(consumer, Duration.ofSeconds(10));

            assertEquals("user-bad", new String(dlqRecord.key(), StandardCharsets.UTF_8));
            assertEquals("NOT_VALID_JSON", new String(dlqRecord.value(), StandardCharsets.UTF_8));
            assertNotNull(dlqRecord.headers().lastHeader("__manual.error.topic"));
            assertNotNull(dlqRecord.headers().lastHeader("__manual.error.partition"));
            assertNotNull(dlqRecord.headers().lastHeader("__manual.error.offset"));
            assertNotNull(dlqRecord.headers().lastHeader("__manual.error.message"));
            assertNotNull(dlqRecord.headers().lastHeader("__manual.error.class"));
        }

        assertTrue(streams.state().isRunningOrRebalancing());
    }

    private void sendInvalidRecord(String key, String value) throws Exception {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {
            producer.send(new ProducerRecord<>(INPUT_TOPIC, key, value)).get();
        }
    }

    private KafkaConsumer<byte[], byte[]> createDlqConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "manual-dlq-it-consumer-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);

        KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(props);
        consumer.subscribe(List.of(DLQ_TOPIC));
        return consumer;
    }

    private ConsumerRecord<byte[], byte[]> waitForSingleRecord(
            KafkaConsumer<byte[], byte[]> consumer,
            Duration timeout
    ) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            for (ConsumerRecord<byte[], byte[]> record : consumer.poll(Duration.ofMillis(250))) {
                if (DLQ_TOPIC.equals(record.topic())) {
                    return record;
                }
            }
        }
        throw new AssertionError("Timed out waiting for a DLQ record");
    }

    private void waitForState(KafkaStreams.State expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (streams.state() == expected) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out waiting for streams state " + expected + ", actual=" + streams.state());
    }
}
