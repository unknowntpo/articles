package io.example;

import org.apache.kafka.clients.producer.ProducerRecord;

@FunctionalInterface
public interface RawDlqSender {
    void send(ProducerRecord<byte[], byte[]> record) throws Exception;
}
