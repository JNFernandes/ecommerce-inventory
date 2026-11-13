package com.ecommerce.inventory.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;

class KafkaListenerStarterTest {

    private final KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);
    private final List<Runnable> scheduled = new ArrayList<>();
    private final Executor capturingExecutor = scheduled::add;

    @Test
    @DisplayName("should hand the listener start-up to the executor instead of starting on the caller's thread")
    void shouldScheduleStartWithoutBlocking() {
        KafkaListenerStarter starter = new KafkaListenerStarter(registry, capturingExecutor);

        starter.startListenersInBackground();

        verify(registry, never()).start();
        assertThat(scheduled).hasSize(1);
    }

    @Test
    @DisplayName("should start every listener container when the scheduled task runs")
    void shouldStartContainersWhenTaskRuns() {
        KafkaListenerStarter starter = new KafkaListenerStarter(registry, capturingExecutor);

        starter.startListenersInBackground();
        scheduled.forEach(Runnable::run);

        verify(registry).start();
    }

    @Test
    @DisplayName("should log and not propagate a failure to start the listener containers")
    void shouldNotPropagateStartFailure() {
        doThrow(new KafkaException("broker unreachable")).when(registry).start();
        KafkaListenerStarter starter = new KafkaListenerStarter(registry, capturingExecutor);

        starter.startListenersInBackground();

        assertThatCode(() -> scheduled.forEach(Runnable::run)).doesNotThrowAnyException();
        verify(registry).start();
    }
}
