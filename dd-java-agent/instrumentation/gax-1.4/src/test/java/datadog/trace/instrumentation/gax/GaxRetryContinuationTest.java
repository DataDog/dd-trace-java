package datadog.trace.instrumentation.gax;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceMatcher.SORT_BY_START_TIME;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.api.core.ApiClock;
import com.google.api.core.NanoClock;
import com.google.api.core.SettableApiFuture;
import com.google.api.gax.retrying.BasicResultRetryAlgorithm;
import com.google.api.gax.retrying.ExponentialRetryAlgorithm;
import com.google.api.gax.retrying.RetryAlgorithm;
import com.google.api.gax.retrying.RetrySettings;
import com.google.api.gax.retrying.RetryingFuture;
import com.google.api.gax.retrying.ScheduledRetryingExecutor;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.threeten.bp.Duration;

class GaxRetryContinuationTest extends AbstractInstrumentationTest {

  @Test
  void supersededAttemptListenerDoesNotLeak() throws Exception {
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    try {
      ScheduledRetryingExecutor<String> executor =
          new ScheduledRetryingExecutor<>(retryAlgorithm(1), scheduler);
      RetryingFuture<String> retryingFuture = executor.createFuture(() -> "ok");

      AgentSpan span = startSpan("gax", "publish");
      try (AgentScope scope = activateSpan(span)) {
        // reserve the attempt slot with a placeholder that is never completed
        SettableApiFuture<String> placeholder = SettableApiFuture.create();
        retryingFuture.setAttemptFuture(placeholder);
        // supersede it with the real attempt future -> must cancel the placeholder's continuation
        SettableApiFuture<String> attempt = SettableApiFuture.create();
        retryingFuture.setAttemptFuture(attempt);
        // real attempt completes -> its listener runs -> resolves normally
        attempt.set("ok");
      }
      span.finish();
      // one trace that will be dropped if the placeholder's continuation is never cancelled
      assertTraces(trace(span().root().operationName("publish")));
    } finally {
      scheduler.shutdownNow();
    }
  }

  @Test
  void singleAttemptSucceedsAndContextNotLeaked() throws Exception {
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    try {
      ScheduledRetryingExecutor<String> executor =
          new ScheduledRetryingExecutor<>(retryAlgorithm(3), scheduler);
      AgentSpan parent = startSpan("gax", "publish");
      CountDownLatch allAttemptsDone = new CountDownLatch(1);
      RetryingFuture<String> future =
          executor.createFuture(
              () -> {
                AgentSpan attempt = startSpan("gax", "attempt");
                try {
                  return "ok";
                } finally {
                  attempt.finish();
                  allAttemptsDone.countDown();
                }
              });

      try (AgentScope scope = activateSpan(parent)) {
        future.setAttemptFuture(executor.submit(future));
        assertEquals("ok", future.get(5, TimeUnit.SECONDS));
      }
      allAttemptsDone.await(5, TimeUnit.SECONDS);
      parent.finish();
      assertTraces(
          trace(
              SORT_BY_START_TIME,
              span().root().operationName("publish"),
              span().childOf(parent.getSpanId()).operationName("attempt")));
    } finally {
      scheduler.shutdownNow();
    }
  }

  @Test
  void retriedAttemptsSucceedAndContextNotLeaked() throws Exception {
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    try {
      ScheduledRetryingExecutor<String> executor =
          new ScheduledRetryingExecutor<>(retryAlgorithm(3), scheduler);
      AtomicInteger count = new AtomicInteger(0);
      AgentSpan parent = startSpan("gax", "publish");
      CountDownLatch allAttemptsDone = new CountDownLatch(3);
      RetryingFuture<String> future =
          executor.createFuture(
              () -> {
                AgentSpan attempt = startSpan("gax", "attempt");
                try {
                  if (count.incrementAndGet() < 3) {
                    throw new RuntimeException("transient");
                  }
                  return "ok";
                } finally {
                  attempt.finish();
                  allAttemptsDone.countDown();
                }
              });

      try (AgentScope scope = activateSpan(parent)) {
        future.setAttemptFuture(executor.submit(future));
        assertEquals("ok", future.get(5, TimeUnit.SECONDS));
      }
      allAttemptsDone.await(5, TimeUnit.SECONDS);
      parent.finish();
      assertTraces(
          trace(
              SORT_BY_START_TIME,
              span().root().operationName("publish"),
              span().childOf(parent.getSpanId()).operationName("attempt"),
              span().childOf(parent.getSpanId()).operationName("attempt"),
              span().childOf(parent.getSpanId()).operationName("attempt")));
    } finally {
      scheduler.shutdownNow();
    }
  }

  @Test
  void exhaustedRetriesContextNotLeaked() throws Exception {
    ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    try {
      ScheduledRetryingExecutor<String> executor =
          new ScheduledRetryingExecutor<>(retryAlgorithm(3), scheduler);
      AgentSpan parent = startSpan("gax", "publish");
      CountDownLatch allAttemptsDone = new CountDownLatch(3);
      RetryingFuture<String> future =
          executor.createFuture(
              () -> {
                AgentSpan attempt = startSpan("gax", "attempt");
                try {
                  throw new RuntimeException("always fails");
                } finally {
                  attempt.finish();
                  allAttemptsDone.countDown();
                }
              });

      try (AgentScope scope = activateSpan(parent)) {
        future.setAttemptFuture(executor.submit(future));
        assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
      }
      allAttemptsDone.await(5, TimeUnit.SECONDS);
      parent.finish();
      assertTraces(
          trace(
              SORT_BY_START_TIME,
              span().root().operationName("publish"),
              span().childOf(parent.getSpanId()).operationName("attempt"),
              span().childOf(parent.getSpanId()).operationName("attempt"),
              span().childOf(parent.getSpanId()).operationName("attempt")));
    } finally {
      scheduler.shutdownNow();
    }
  }

  private static RetryAlgorithm<String> retryAlgorithm(int maxAttempts) {
    RetrySettings settings =
        RetrySettings.newBuilder()
            .setMaxAttempts(maxAttempts)
            .setInitialRetryDelay(Duration.ofMillis(1))
            .setRetryDelayMultiplier(1.0)
            .setMaxRetryDelay(Duration.ofMillis(10))
            .setInitialRpcTimeout(Duration.ofSeconds(5))
            .setRpcTimeoutMultiplier(1.0)
            .setMaxRpcTimeout(Duration.ofSeconds(5))
            .setTotalTimeout(Duration.ofSeconds(30))
            .build();
    ApiClock clock = NanoClock.getDefaultClock();
    return new RetryAlgorithm<>(
        new BasicResultRetryAlgorithm<>(), new ExponentialRetryAlgorithm(settings, clock));
  }
}
