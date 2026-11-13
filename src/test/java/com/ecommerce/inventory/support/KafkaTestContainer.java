package com.ecommerce.inventory.support;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.testcontainers.kafka.ConfluentKafkaContainer;

/** One Kafka broker per JVM (same image as ecommerce-infra), with the service topics created. */
public final class KafkaTestContainer {

    /** Topics the service consumes and produces. */
    public static final List<String> TOPICS =
            List.of("orders.order-placed", "inventory.stock-reserved", "inventory.stock-rejected");

    private static final ConfluentKafkaContainer INSTANCE = start();

    private KafkaTestContainer() {}

    /**
     * Returns the started broker.
     *
     * @return the shared Kafka container
     */
    public static ConfluentKafkaContainer instance() {
        return INSTANCE;
    }

    private static ConfluentKafkaContainer start() {
        ConfluentKafkaContainer container = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.6.1");
        container.start();
        createTopics(container.getBootstrapServers());
        return container;
    }

    private static void createTopics(String bootstrapServers) {
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers))) {
            List<NewTopic> topics = TOPICS.stream()
                    .map(name -> new NewTopic(name, 3, (short) 1))
                    .toList();
            admin.createTopics(topics).all().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while creating test topics", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Could not create test topics", e);
        }
    }
}
