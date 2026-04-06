package io.example;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Properties;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ClickEventManualDlqTopologyTest {

    private static final String INPUT_TOPIC  = "click-events";
    private static final String OUTPUT_TOPIC = "click-events-output";
    private static final String DLQ_TOPIC    = "click-events-dlq";

    @Mock
    KafkaProducer<String, String> mockProducer;

    private TopologyTestDriver driver;
    private TestInputTopic<String, String> inputTopic;
    private TestOutputTopic<String, String> outputTopic;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() throws Exception {
        lenient().when(mockProducer.send(any())).thenReturn(mock(Future.class));

        ClickEventManualDlqTopology topology =
                new ClickEventManualDlqTopology(mockProducer, DLQ_TOPIC);

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
    void validRecord_goesToOutput() {
        inputTopic.pipeInput("user-1", "{\"ad_id\":\"banner-A\",\"count\":3}");

        List<KeyValue<String, String>> records = outputTopic.readKeyValuesToList();
        assertEquals(1, records.size());
        assertEquals("user=user-1 clicked ad=banner-A count=3", records.get(0).value);
        verifyNoInteractions(mockProducer);
    }

    @SuppressWarnings("unchecked")
    @Test
    void invalidRecord_routedToDlq_notInOutput() {
        inputTopic.pipeInput("user-bad", "NOT_VALID_JSON");

        assertTrue(outputTopic.isEmpty());

        ArgumentCaptor<ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(mockProducer).send(captor.capture());

        ProducerRecord<String, String> dlqRecord = captor.getValue();
        assertEquals(DLQ_TOPIC,        dlqRecord.topic());
        assertEquals("user-bad",       dlqRecord.key());
        assertEquals("NOT_VALID_JSON", dlqRecord.value());
    }

    @SuppressWarnings("unchecked")
    @Test
    void mixedRecords_correctRouting() {
        inputTopic.pipeInput("user-1",   "{\"ad_id\":\"banner-A\",\"count\":3}");
        inputTopic.pipeInput("user-2",   "{\"ad_id\":\"video-B\",\"count\":1}");
        inputTopic.pipeInput("user-bad", "NOT_VALID_JSON");

        assertEquals(2, outputTopic.readKeyValuesToList().size());

        ArgumentCaptor<ProducerRecord<String, String>> captor =
                ArgumentCaptor.forClass(ProducerRecord.class);
        verify(mockProducer, times(1)).send(captor.capture());
        assertEquals("user-bad", captor.getValue().key());
    }
}
