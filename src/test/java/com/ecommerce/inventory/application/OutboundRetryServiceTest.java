package com.ecommerce.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ecommerce.inventory.application.port.OutboundDeadLetter;
import com.ecommerce.inventory.application.port.OutboundDeadLetterRepository;
import com.ecommerce.inventory.application.port.OutboundMessage;
import com.ecommerce.inventory.application.port.OutboundMessagePublisher;
import com.ecommerce.inventory.application.port.PublishFailedException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class OutboundRetryServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-14T09:00:00Z");
    private static final OutboundRetryPolicy POLICY =
            new OutboundRetryPolicy(3, 5, Duration.ofSeconds(30), 2.0, Duration.ofMinutes(15));

    @Mock
    private OutboundDeadLetterRepository deadLetters;

    @Mock
    private OutboundMessagePublisher publisher;

    private final List<String> journal = new ArrayList<>();
    private OutboundRetryService service;

    @BeforeEach
    void setUp() {
        service = new OutboundRetryService(
                deadLetters, publisher, POLICY, new JournalingTransactions(journal), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("should publish the stored message unchanged and mark it resolved, each row in its own transaction")
    void shouldDeliverEachRowInItsOwnTransaction() {
        OutboundDeadLetter first = row(0);
        OutboundDeadLetter second = row(2);
        when(deadLetters.claimNextDue())
                .thenReturn(Optional.of(first))
                .thenReturn(Optional.of(second))
                .thenReturn(Optional.empty());
        doAnswer(invocation -> journal.add("publish")).when(publisher).publish(any());
        doAnswer(invocation -> journal.add("resolve")).when(deadLetters).markResolved(any(), any());

        RetryRoundResult result = service.retryDueBatch();

        assertThat(result).isEqualTo(new RetryRoundResult(2, 2, 0, 0));
        InOrder order = inOrder(publisher, deadLetters);
        order.verify(publisher).publish(first.message());
        order.verify(deadLetters).markResolved(first.id(), NOW);
        order.verify(publisher).publish(second.message());
        order.verify(deadLetters).markResolved(second.id(), NOW);
        assertThat(journal)
                .containsExactly(
                        "begin", "publish", "resolve", "commit", "begin", "publish", "resolve", "commit", "begin",
                        "commit");
    }

    @Test
    @DisplayName("should attempt at most batch size rows in one round")
    void shouldStopAtBatchSize() {
        when(deadLetters.claimNextDue()).thenAnswer(invocation -> Optional.of(row(0)));

        RetryRoundResult result = service.retryDueBatch();

        assertThat(result).isEqualTo(new RetryRoundResult(3, 3, 0, 0));
        verify(deadLetters, times(3)).claimNextDue();
        verify(publisher, times(3)).publish(any());
    }

    @Test
    @DisplayName("should claim nothing and publish nothing when no row is due")
    void shouldDoNothingWhenIdle(CapturedOutput output) {
        when(deadLetters.claimNextDue()).thenReturn(Optional.empty());

        RetryRoundResult result = service.retryDueBatch();

        assertThat(result).isEqualTo(RetryRoundResult.NONE);
        verifyNoInteractions(publisher);
        assertThat(output).doesNotContain("Outbound retry round:");
    }

    @Test
    @DisplayName("should log one summary line for a round that claimed rows")
    void shouldLogRoundSummary(CapturedOutput output) {
        when(deadLetters.claimNextDue()).thenReturn(Optional.of(row(0))).thenReturn(Optional.empty());

        service.retryDueBatch();

        assertThat(output).contains("Outbound retry round: claimed=1 resolved=1 failed=0 exhausted=0");
    }

    @Test
    @DisplayName("should record a failed attempt with the backoff delay and end the round")
    void shouldBackOffAndFailFast(CapturedOutput output) {
        OutboundDeadLetter failing = row(1);
        when(deadLetters.claimNextDue()).thenReturn(Optional.of(failing)).thenReturn(Optional.of(row(0)));
        doThrow(new PublishFailedException("not acknowledged", new IllegalStateException("broker down")))
                .when(publisher)
                .publish(failing.message());

        RetryRoundResult result = service.retryDueBatch();

        assertThat(result).isEqualTo(new RetryRoundResult(1, 0, 1, 0));
        verify(deadLetters)
                .recordFailedAttempt(
                        eq(failing.id()),
                        eq(2),
                        eq("com.ecommerce.inventory.application.port.PublishFailedException: not acknowledged"
                                + " | caused by java.lang.IllegalStateException: broker down"),
                        eq(NOW.plus(Duration.ofMinutes(1))));
        verify(deadLetters, never()).markResolved(any(), any());
        verify(deadLetters, times(1)).claimNextDue();
        assertThat(journal).containsExactly("begin", "commit");
        assertThat(output)
                .contains("WARN")
                .contains("Outbound retry of dead letter " + failing.id() + " to inventory.stock-reserved failed,"
                        + " attempt 2/5, next attempt at " + NOW.plus(Duration.ofMinutes(1)))
                .contains("Outbound retry round: claimed=1 resolved=0 failed=1 exhausted=0");
    }

    @Test
    @DisplayName("should mark the row exhausted and log it at error level after the last allowed attempt")
    void shouldExhaustAfterLastAttempt(CapturedOutput output) {
        OutboundDeadLetter last = row(4);
        when(deadLetters.claimNextDue()).thenReturn(Optional.of(last));
        doThrow(new PublishFailedException("timeout", null)).when(publisher).publish(any());

        RetryRoundResult result = service.retryDueBatch();

        assertThat(result).isEqualTo(new RetryRoundResult(1, 0, 0, 1));
        verify(deadLetters).markExhausted(eq(last.id()), eq(5), anyString());
        verify(deadLetters, never()).recordFailedAttempt(any(), anyInt(), any(), any());
        assertThat(output)
                .contains("ERROR")
                .contains("Outbound dead letter " + last.id() + " exhausted after 5 attempts")
                .contains("eventId=" + last.message().eventId())
                .contains("topic=inventory.stock-reserved")
                .contains("key=" + last.message().key())
                .contains("lastError=com.ecommerce.inventory.application.port.PublishFailedException: timeout");
    }

    @Test
    @DisplayName("should log an unexpected error and return normally instead of throwing")
    void shouldNeverThrow(CapturedOutput output) {
        when(deadLetters.claimNextDue()).thenThrow(new IllegalStateException("database gone"));

        RetryRoundResult result = service.retryDueBatch();

        assertThat(result).isEqualTo(RetryRoundResult.NONE);
        assertThat(journal).containsExactly("begin", "rollback");
        assertThat(output)
                .contains("Outbound retry round stopped by an unexpected error")
                .contains("database gone");
    }

    @Test
    @DisplayName("should claim nothing after stop")
    void shouldClaimNothingAfterStop() {
        assertThat(service.stop(Duration.ofSeconds(1))).isTrue();

        RetryRoundResult result = service.retryDueBatch();

        assertThat(result).isEqualTo(RetryRoundResult.NONE);
        verifyNoInteractions(deadLetters, publisher);
    }

    @Test
    @DisplayName("should let the row in progress finish on stop and claim nothing more")
    void shouldFinishRowInProgressOnStop() throws Exception {
        OutboundDeadLetter inProgress = row(0);
        CountDownLatch publishing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(deadLetters.claimNextDue()).thenReturn(Optional.of(inProgress)).thenReturn(Optional.of(row(0)));
        doAnswer(invocation -> {
                    publishing.countDown();
                    assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                    return null;
                })
                .when(publisher)
                .publish(inProgress.message());

        CompletableFuture<RetryRoundResult> roundInProgress = CompletableFuture.supplyAsync(service::retryDueBatch);
        assertThat(publishing.await(5, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Boolean> stopped = CompletableFuture.supplyAsync(() -> service.stop(Duration.ofSeconds(5)));
        release.countDown();

        assertThat(stopped.get(5, TimeUnit.SECONDS)).isTrue();
        assertThat(roundInProgress.get(5, TimeUnit.SECONDS)).isEqualTo(new RetryRoundResult(1, 1, 0, 0));
        verify(deadLetters).markResolved(inProgress.id(), NOW);
        verify(deadLetters, times(1)).claimNextDue();
    }

    @Test
    @DisplayName("should give up waiting on stop after the timeout when a round does not finish")
    void shouldBoundTheStopWait() throws Exception {
        CountDownLatch publishing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(deadLetters.claimNextDue()).thenReturn(Optional.of(row(0))).thenReturn(Optional.empty());
        doAnswer(invocation -> {
                    publishing.countDown();
                    assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                    return null;
                })
                .when(publisher)
                .publish(any());
        CompletableFuture<RetryRoundResult> roundInProgress = CompletableFuture.supplyAsync(service::retryDueBatch);
        assertThat(publishing.await(5, TimeUnit.SECONDS)).isTrue();

        boolean stopped = service.stop(Duration.ofMillis(100));
        release.countDown();

        assertThat(stopped).isFalse();
        assertThat(roundInProgress.get(5, TimeUnit.SECONDS).resolved()).isEqualTo(1);
    }

    @Test
    @DisplayName("should not start a second round while one is running")
    void shouldNotOverlapRounds() throws Exception {
        CountDownLatch publishing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(deadLetters.claimNextDue()).thenReturn(Optional.of(row(0))).thenReturn(Optional.empty());
        doAnswer(invocation -> {
                    publishing.countDown();
                    assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                    return null;
                })
                .when(publisher)
                .publish(any());
        CompletableFuture<RetryRoundResult> first = CompletableFuture.supplyAsync(service::retryDueBatch);
        assertThat(publishing.await(5, TimeUnit.SECONDS)).isTrue();

        RetryRoundResult second = service.retryDueBatch();
        release.countDown();

        assertThat(second).isEqualTo(RetryRoundResult.NONE);
        assertThat(first.get(5, TimeUnit.SECONDS).resolved()).isEqualTo(1);
    }

    private static OutboundDeadLetter row(int retryCount) {
        UUID eventId = UUID.randomUUID();
        return new OutboundDeadLetter(
                UUID.randomUUID(),
                new OutboundMessage(eventId, "inventory.stock-reserved", "res-" + eventId, "{\"eventId\":1}"),
                retryCount);
    }
}
