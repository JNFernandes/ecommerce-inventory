package com.ecommerce.inventory.support;

import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

/** Produces raw messages and reads published ones, standing in for the other services. */
public final class TestKafkaClient {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final String bootstrapServers;

    /**
     * Creates a client for a broker.
     *
     * @param bootstrapServers broker address
     */
    public TestKafkaClient(String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    /**
     * Sends a raw value and waits for the broker acknowledgement.
     *
     * @param topic topic
     * @param key message key
     * @param value raw message value
     * @return partition and offset the record was written to
     */
    public RecordMetadata send(String topic, String key, String value) {
        Map<String, Object> config = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(config)) {
            return producer.send(new ProducerRecord<>(topic, key, value)).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while sending a test message", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Could not send a test message to " + topic, e);
        }
    }

    /**
     * Reads the offset a consumer group has committed for a partition.
     *
     * @param groupId consumer group
     * @param topic topic
     * @param partition partition
     * @return the committed offset, or -1 when nothing is committed
     */
    public long committedOffset(String groupId, String topic, int partition) {
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers))) {
            OffsetAndMetadata committed = admin.listConsumerGroupOffsets(groupId)
                    .partitionsToOffsetAndMetadata()
                    .get(10, TimeUnit.SECONDS)
                    .get(new TopicPartition(topic, partition));
            return committed == null ? -1 : committed.offset();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while reading committed offsets", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Could not read committed offsets of " + groupId, e);
        }
    }

    /**
     * Waits until at least {@code count} records whose JSON {@code orderId} equals the given order id
     * are on the topic, then returns all of them.
     *
     * @param topic topic to read from the beginning
     * @param orderId order id to match
     * @param count minimum number of records to wait for
     * @param timeout maximum wait
     * @return the matching records
     */
    public List<ConsumerRecord<String, String>> awaitRecordsForOrder(
            String topic, UUID orderId, int count, Duration timeout) {
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        await().atMost(timeout).pollInterval(Duration.ofMillis(100)).until(() -> {
            found.clear();
            found.addAll(recordsForOrder(topic, orderId));
            return found.size() >= count;
        });
        return List.copyOf(found);
    }

    /**
     * Reads every record currently on the topic whose JSON {@code orderId} equals the given order id.
     *
     * @param topic topic to read from the beginning
     * @param orderId order id to match
     * @return the matching records
     */
    public List<ConsumerRecord<String, String>> recordsForOrder(String topic, UUID orderId) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG,
                "test-reader-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                false,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class);
        List<ConsumerRecord<String, String>> matching = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(topic));
            int emptyPolls = 0;
            while (emptyPolls < 2) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(300));
                if (records.isEmpty()) {
                    emptyPolls++;
                }
                for (ConsumerRecord<String, String> record : records) {
                    if (orderId.toString()
                            .equals(json(record.value()).path("orderId").asText())) {
                        matching.add(record);
                    }
                }
            }
        }
        return matching;
    }

    /**
     * Parses a JSON text.
     *
     * @param value JSON text
     * @return the parsed tree
     */
    public static JsonNode json(String value) {
        try {
            return JSON.readTree(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Not JSON: " + value, e);
        }
    }
}
