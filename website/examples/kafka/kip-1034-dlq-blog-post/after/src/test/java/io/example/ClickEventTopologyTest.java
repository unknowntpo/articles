package io.example;

import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.TopologyTestDriver;
import org.apache.kafka.streams.errors.LogAndContinueExceptionHandler;
import org.apache.kafka.streams.test.TestRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ClickEventTopologyTest {

    private static final String INPUT_TOPIC  = "click-events";
    private static final String OUTPUT_TOPIC = "click-events-output";
    private static final String DLQ_TOPIC    = "click-events-dlq";

    private TopologyTestDriver driver;
    private TestInputTopic<String, String> inputTopic;
    private TestOutputTopic<String, String> outputTopic;
    private TestOutputTopic<byte[], byte[]> dlqTopic;

    @BeforeEach
    void setUp() {
        ClickEventTopology topology = new ClickEventTopology();

        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "test-click-event-after");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:9092");
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG,   Serdes.String().getClass());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass());
        props.put(StreamsConfig.ERRORS_DEAD_LETTER_QUEUE_TOPIC_NAME_CONFIG, DLQ_TOPIC);
        props.put(StreamsConfig.DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG,
                LogAndContinueExceptionHandler.class);

        driver      = new TopologyTestDriver(topology.build(INPUT_TOPIC, OUTPUT_TOPIC), props);
        inputTopic  = driver.createInputTopic(INPUT_TOPIC,  new StringSerializer(),    new StringSerializer());
        outputTopic = driver.createOutputTopic(OUTPUT_TOPIC, new StringDeserializer(), new StringDeserializer());
        dlqTopic    = driver.createOutputTopic(DLQ_TOPIC,    new ByteArrayDeserializer(), new ByteArrayDeserializer());
    }

    @AfterEach
    void tearDown() {
        driver.close();
    }

    @Test
    void testValidRecordGoesToOutput() {
        inputTopic.pipeInput("user-1", "{\"ad_id\":\"banner-A\",\"count\":3}");

        List<KeyValue<String, String>> records = outputTopic.readKeyValuesToList();
        assertEquals(1, records.size());
        assertEquals("user clicked ad=banner-A count=3", records.get(0).value);
        assertTrue(dlqTopic.isEmpty());
    }

    @Test
    void testInvalidRecordGoesToDlq() {
        inputTopic.pipeInput("user-bad", "NOT_VALID_JSON");

        assertTrue(outputTopic.isEmpty());
        assertFalse(dlqTopic.isEmpty());
    }

    @Test
    void testDlqRecordHasStreamsErrorHeaders() {
        inputTopic.pipeInput("user-bad", "NOT_VALID_JSON");

        TestRecord<byte[], byte[]> dlqRecord = dlqTopic.readRecord();
        Headers headers = dlqRecord.headers();

        Set<String> headerNames = new HashSet<>();
        for (Header h : headers) headerNames.add(h.key());

        assertTrue(headerNames.contains("__streams.errors.exception"),  "missing exception header");
        assertTrue(headerNames.contains("__streams.errors.message"),    "missing message header");
        assertTrue(headerNames.contains("__streams.errors.stacktrace"), "missing stacktrace header");
        assertTrue(headerNames.contains("__streams.errors.topic"),      "missing topic header");
        assertTrue(headerNames.contains("__streams.errors.partition"),  "missing partition header");
        assertTrue(headerNames.contains("__streams.errors.offset"),     "missing offset header");

        Header topicHeader = headers.lastHeader("__streams.errors.topic");
        assertNotNull(topicHeader);
        assertEquals(INPUT_TOPIC, new String(topicHeader.value()));
    }

    @Test
    void testMixedRecordsCorrectRouting() {
        inputTopic.pipeInput("user-1",    "{\"ad_id\":\"banner-A\",\"count\":3}");
        inputTopic.pipeInput("user-2",    "{\"ad_id\":\"video-B\",\"count\":1}");
        inputTopic.pipeInput("user-3",    "{\"ad_id\":\"sidebar-C\",\"count\":7}");
        inputTopic.pipeInput("user-bad1", "NOT_VALID_JSON");
        inputTopic.pipeInput("user-bad2", "{broken json");

        assertEquals(3, outputTopic.readKeyValuesToList().size());
        assertEquals(2, dlqTopic.readKeyValuesToList().size());
    }
}
