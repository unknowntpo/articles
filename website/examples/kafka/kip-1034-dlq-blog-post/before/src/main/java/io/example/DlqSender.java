package io.example;

import org.apache.kafka.clients.producer.ProducerRecord;

@FunctionalInterface
public interface DlqSender {
    // Small seam so tests can spy on a real sender without mocking KafkaProducer/Future internals.
    void send(ProducerRecord<String, String> record) throws Exception;
}
