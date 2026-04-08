package io.example;

import org.apache.kafka.clients.producer.ProducerRecord;

@FunctionalInterface
public interface DlqSender {
    void send(ProducerRecord<String, String> record) throws Exception;
}
