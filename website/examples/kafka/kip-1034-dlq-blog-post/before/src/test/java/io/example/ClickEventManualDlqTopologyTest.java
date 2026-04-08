package io.example;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TestOutputTopic;
import org.apache.kafka.streams.TopologyTestDriver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClickEventManualDlqTopologyTest {

    private static final String INPUT_TOPIC  = "click-events";
    private static final String OUTPUT_TOPIC = "click-events-output";
    private static final String DLQ_TOPIC    = "click-events-dlq";

    private TopologyTestDriver driver;
    private TestInputTopic<String, String> inputTopic;
    private TestOutputTopic<String, String> outputTopic;
    private RecordingDlqSender dlqSender;

    @BeforeEach
    void setUp() {
        // Use a real in-memory sender plus spy so the test checks sent records directly
        // without coupling itself to KafkaProducer.send()/Future mocking details.
        dlqSender = spy(new RecordingDlqSender());

        ClickEventManualDlqTopology topology =
                new ClickEventManualDlqTopology(dlqSender, DLQ_TOPIC);

        Properties props = new Properties();
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, "test-manual-dlq");
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "dummy:9092");
        props.put(StreamsConfig.DEFAULT_KEY_SERDE_CLASS_CONFIG,   Serdes.String().getClass());
        props.put(StreamsConfig.DEFAULT_VALUE_SERDE_CLASS_CONFIG, Serdes.String().getClass());

        driver      = new TopologyTestDriver(topology.build(INPUT_TOPIC, OUTPUT_TOPIC), props);
        inputTopic  = driver.createInputTopic(INPUT_TOPIC,  new StringSerializer(),   new StringSerializer());
        outputTopic = driver.createOutputTopic(OUTPUT_TOPIC, new StringDeserializer(), new StringDeserializer());
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
        assertEquals("user=user-1 clicked ad=banner-A count=3", records.get(0).value);
        verifyNoInteractions(dlqSender);
    }

    @SuppressWarnings("unchecked")
    @Test
    void testInvalidRecordRoutedToDlqNotInOutput() {
        inputTopic.pipeInput("user-bad", "NOT_VALID_JSON");

        assertTrue(outputTopic.isEmpty());

        ArgumentCaptor<ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(dlqSender).send(captor.capture());

        ProducerRecord<String, String> dlqRecord = captor.getValue();
        assertEquals(DLQ_TOPIC,        dlqRecord.topic());
        assertEquals("user-bad",       dlqRecord.key());
        assertEquals("NOT_VALID_JSON", dlqRecord.value());
        assertEquals(1, dlqSender.records.size());
    }

    @SuppressWarnings("unchecked")
    @Test
    void testMixedRecordsCorrectRouting() {
        inputTopic.pipeInput("user-1",   "{\"ad_id\":\"banner-A\",\"count\":3}");
        inputTopic.pipeInput("user-2",   "{\"ad_id\":\"video-B\",\"count\":1}");
        inputTopic.pipeInput("user-bad", "NOT_VALID_JSON");

        assertEquals(2, outputTopic.readKeyValuesToList().size());

        ArgumentCaptor<ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(dlqSender, times(1)).send(captor.capture());
        assertEquals("user-bad", captor.getValue().key());
        assertEquals(1, dlqSender.records.size());
    }

    private static class RecordingDlqSender implements DlqSender {
        private final List<ProducerRecord<String, String>> records = new ArrayList<>();

        @Override
        public void send(ProducerRecord<String, String> record) {
            records.add(record);
        }
    }
}
