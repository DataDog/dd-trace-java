package datadog.trace.core.monitor;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import datadog.metrics.api.statsd.StatsDClient;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.common.writer.RemoteApi;
import datadog.trace.core.DDSpan;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.tabletest.junit.TableTest;

@ExtendWith(MockitoExtension.class)
class HealthMetricsTest {

  @Mock StatsDClient statsD;

  @Test
  void testOnShutdown() {
    TracerHealthMetrics healthMetrics = new TracerHealthMetrics(statsD);
    healthMetrics.onShutdown(true);
    verifyNoInteractions(statsD);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("testOnPublishArguments")
  void testOnPublish(String scenario, List<DDSpan> trace, int samplingPriority, String priorityName)
      throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(trace.isEmpty() ? 1 : 2);
    try (TracerHealthMetrics healthMetrics =
        new TracerHealthMetrics(new Latched(statsD, latch), 100, TimeUnit.MILLISECONDS)) {
      healthMetrics.start();
      healthMetrics.onPublish(trace, samplingPriority);
      assertTrue(latch.await(10, TimeUnit.SECONDS));
    }
    verify(statsD).count("queue.enqueued.traces", 1L, "priority:" + priorityName);
    if (!trace.isEmpty()) {
      verify(statsD).count("queue.enqueued.spans", (long) trace.size());
    }
    verifyNoMoreInteractions(statsD);
  }

  static Stream<Arguments> testOnPublishArguments() {
    List<DDSpan> emptyTrace = Collections.emptyList();
    List<DDSpan> twoSpanTrace = Arrays.<DDSpan>asList(null, null);
    return Stream.of(
        arguments(
            "empty trace user_drop", emptyTrace, (int) PrioritySampling.USER_DROP, "user_drop"),
        arguments(
            "two span trace user_drop",
            twoSpanTrace,
            (int) PrioritySampling.USER_DROP,
            "user_drop"),
        arguments(
            "empty trace sampler_keep",
            emptyTrace,
            (int) PrioritySampling.SAMPLER_KEEP,
            "sampler_keep"),
        arguments(
            "two span trace sampler_keep",
            twoSpanTrace,
            (int) PrioritySampling.SAMPLER_KEEP,
            "sampler_keep"));
  }

  @TableTest({
    "scenario     | samplingPriority | samplingTag          ",
    "sampler_keep | 1                | priority:sampler_keep",
    "user_keep    | 2                | priority:user_keep   ",
    "user_drop    | -1               | priority:user_drop   ",
    "sampler_drop | 0                | priority:sampler_drop",
    "unset        | -128             | priority:unset       "
  })
  @ParameterizedTest(name = "testOnFailedPublish [{index}]")
  void testOnFailedPublish(int samplingPriority, String samplingTag) throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(2);
    try (TracerHealthMetrics healthMetrics =
        new TracerHealthMetrics(new Latched(statsD, latch), 100, TimeUnit.MILLISECONDS)) {
      healthMetrics.start();
      healthMetrics.onFailedPublish(samplingPriority, 1);
      assertTrue(latch.await(2, TimeUnit.SECONDS));
    }
    verify(statsD).count("queue.dropped.traces", 1L, samplingTag);
    verify(statsD).count("queue.dropped.spans", 1L, samplingTag);
    verifyNoMoreInteractions(statsD);
  }

  @TableTest({
    "scenario   | droppedSpans",
    "1 dropped  | 1           ",
    "42 dropped | 42          ",
    "3 dropped  | 3           "
  })
  @ParameterizedTest(name = "testOnPartialPublish [{index}]")
  void testOnPartialPublish(int droppedSpans) throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(2);
    try (TracerHealthMetrics healthMetrics =
        new TracerHealthMetrics(new Latched(statsD, latch), 100, TimeUnit.MILLISECONDS)) {
      healthMetrics.start();
      healthMetrics.onPartialPublish(droppedSpans);
      assertTrue(latch.await(10, TimeUnit.SECONDS));
    }
    verify(statsD).count("queue.partial.traces", 1L);
    verify(statsD).count("queue.dropped.spans", (long) droppedSpans, "priority:sampler_drop");
    verifyNoMoreInteractions(statsD);
  }

  @Test
  void testOnScheduleFlush() {
    TracerHealthMetrics healthMetrics = new TracerHealthMetrics(statsD);
    healthMetrics.onScheduleFlush(true);
    verifyNoInteractions(statsD);
  }

  @Test
  void testOnFlush() {
    TracerHealthMetrics healthMetrics = new TracerHealthMetrics(statsD);
    healthMetrics.onFlush(true);
    verifyNoInteractions(statsD);
  }

  @Test
  void testOnSerialize() throws InterruptedException {
    int bytes = ThreadLocalRandom.current().nextInt(10000);
    CountDownLatch latch = new CountDownLatch(1);
    try (TracerHealthMetrics healthMetrics =
        new TracerHealthMetrics(new Latched(statsD, latch), 100, TimeUnit.MILLISECONDS)) {
      healthMetrics.start();
      healthMetrics.onSerialize(bytes);
      assertTrue(latch.await(10, TimeUnit.SECONDS));
    }
    verify(statsD).count("queue.enqueued.bytes", (long) bytes);
    verifyNoMoreInteractions(statsD);
  }

  @Test
  void testOnFailedSerialize() {
    TracerHealthMetrics healthMetrics = new TracerHealthMetrics(statsD);
    healthMetrics.onFailedSerialize(null, null);
    verifyNoInteractions(statsD);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("testOnSendArguments")
  void testOnSend(String scenario, RemoteApi.Response response) throws InterruptedException {
    int traceCount = ThreadLocalRandom.current().nextInt(1, 100);
    int sendSize = ThreadLocalRandom.current().nextInt(1, 100);
    int latchCount =
        3 + (response.exception().isPresent() ? 1 : 0) + (response.status().isPresent() ? 1 : 0);
    CountDownLatch latch = new CountDownLatch(latchCount);
    try (TracerHealthMetrics healthMetrics =
        new TracerHealthMetrics(new Latched(statsD, latch), 100, TimeUnit.MILLISECONDS)) {
      healthMetrics.start();
      healthMetrics.onSend(traceCount, sendSize, response);
      assertTrue(latch.await(10, TimeUnit.SECONDS));
    }
    verifySendAttempt(response, traceCount, sendSize);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("testOnFailedSendArguments")
  void testOnFailedSend(String scenario, RemoteApi.Response response) throws InterruptedException {
    int traceCount = ThreadLocalRandom.current().nextInt(1, 100);
    int sendSize = ThreadLocalRandom.current().nextInt(1, 100);
    int latchCount =
        3 + (response.exception().isPresent() ? 1 : 0) + (response.status().isPresent() ? 1 : 0);
    CountDownLatch latch = new CountDownLatch(latchCount);
    try (TracerHealthMetrics healthMetrics =
        new TracerHealthMetrics(new Latched(statsD, latch), 100, TimeUnit.MILLISECONDS)) {
      healthMetrics.start();
      healthMetrics.onFailedSend(traceCount, sendSize, response);
      assertTrue(latch.await(10, TimeUnit.SECONDS));
    }
    verifySendAttempt(response, traceCount, sendSize);
  }

  private void verifySendAttempt(RemoteApi.Response response, int traceCount, int sendSize) {
    verify(statsD).count("api.requests.total", 1L);
    verify(statsD).count("flush.traces.total", (long) traceCount);
    verify(statsD).count("flush.bytes.total", (long) sendSize);
    if (response.exception().isPresent()) {
      verify(statsD).count("api.errors.total", 1L);
    }
    if (response.status().isPresent()) {
      verify(statsD)
          .incrementCounter("api.responses.total", "status:" + response.status().getAsInt());
    }
    verifyNoMoreInteractions(statsD);
  }

  static Stream<Arguments> testOnSendArguments() {
    return Stream.of(
        arguments(
            "success with status",
            RemoteApi.Response.success(ThreadLocalRandom.current().nextInt(1, 100))),
        arguments(
            "failed with status",
            RemoteApi.Response.failed(ThreadLocalRandom.current().nextInt(1, 100))),
        arguments(
            "success with status and exception",
            RemoteApi.Response.success(
                ThreadLocalRandom.current().nextInt(1, 100), new Throwable())),
        arguments("failed with exception", RemoteApi.Response.failed(new Throwable())));
  }

  static Stream<Arguments> testOnFailedSendArguments() {
    return testOnSendArguments();
  }

  @Test
  void testOnCreateTrace() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);
    try (TracerHealthMetrics healthMetrics =
        new TracerHealthMetrics(new Latched(statsD, latch), 100, TimeUnit.MILLISECONDS)) {
      healthMetrics.start();
      healthMetrics.onCreateTrace();
      assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
    verify(statsD).count("trace.pending.created", 1L);
    verifyNoMoreInteractions(statsD);
  }

  @Test
  void testOnCreateSpan() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);
    try (TracerHealthMetrics healthMetrics =
        new TracerHealthMetrics(new Latched(statsD, latch), 100, TimeUnit.MILLISECONDS)) {
      healthMetrics.start();
      healthMetrics.onCreateSpan();
      assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
    verify(statsD).count("span.pending.created", 1L);
    verifyNoMoreInteractions(statsD);
  }

  @Test
  void testOnCancelContinuation() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);
    try (TracerHealthMetrics healthMetrics =
        new TracerHealthMetrics(new Latched(statsD, latch), 100, TimeUnit.MILLISECONDS)) {
      healthMetrics.start();
      healthMetrics.onCancelContinuation();
      assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
    verify(statsD).count("span.continuations.canceled", 1L);
    verifyNoMoreInteractions(statsD);
  }

  @Test
  void testOnFinishContinuation() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);
    try (TracerHealthMetrics healthMetrics =
        new TracerHealthMetrics(new Latched(statsD, latch), 100, TimeUnit.MILLISECONDS)) {
      healthMetrics.start();
      healthMetrics.onFinishContinuation();
      assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
    verify(statsD).count("span.continuations.finished", 1L);
    verifyNoMoreInteractions(statsD);
  }

  @Test
  void testOnSingleSpanSample() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);
    try (TracerHealthMetrics healthMetrics =
        new TracerHealthMetrics(new Latched(statsD, latch), 100, TimeUnit.MILLISECONDS)) {
      healthMetrics.start();
      healthMetrics.onSingleSpanSample();
      assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
    verify(statsD).count("span.sampling.sampled", 1L, "sampler:single-span");
    verifyNoMoreInteractions(statsD);
  }

  @Test
  void testOnSingleSpanUnsampled() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);
    try (TracerHealthMetrics healthMetrics =
        new TracerHealthMetrics(new Latched(statsD, latch), 100, TimeUnit.MILLISECONDS)) {
      healthMetrics.start();
      healthMetrics.onSingleSpanUnsampled();
      assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
    verify(statsD).count("span.sampling.unsampled", 1L, "sampler:single-span");
    verifyNoMoreInteractions(statsD);
  }

  @Test
  void testOnFinishSpan() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);
    try (TracerHealthMetrics healthMetrics =
        new TracerHealthMetrics(new Latched(statsD, latch), 100, TimeUnit.MILLISECONDS)) {
      healthMetrics.start();
      healthMetrics.onFinishSpan();
      assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
    verify(statsD).count("span.pending.finished", 1L);
    verifyNoMoreInteractions(statsD);
  }

  @Test
  void testOnActivateScope() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);
    try (TracerHealthMetrics healthMetrics =
        new TracerHealthMetrics(new Latched(statsD, latch), 100, TimeUnit.MILLISECONDS)) {
      healthMetrics.start();
      healthMetrics.onActivateScope();
      assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
    verify(statsD).count("scope.activate.count", 1L);
    verifyNoMoreInteractions(statsD);
  }

  @Test
  void testOnCloseScope() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);
    try (TracerHealthMetrics healthMetrics =
        new TracerHealthMetrics(new Latched(statsD, latch), 100, TimeUnit.MILLISECONDS)) {
      healthMetrics.start();
      healthMetrics.onCloseScope();
      assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
    verify(statsD).count("scope.close.count", 1L);
    verifyNoMoreInteractions(statsD);
  }

  @TableTest({"scenario  | manual", "automatic | false ", "manual    | true  "})
  @ParameterizedTest(name = "testOnScopeCloseError [{index}]")
  void testOnScopeCloseError(boolean manual) throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(manual ? 2 : 1);
    try (TracerHealthMetrics healthMetrics =
        new TracerHealthMetrics(new Latched(statsD, latch), 100, TimeUnit.MILLISECONDS)) {
      healthMetrics.start();
      healthMetrics.onScopeCloseError(manual);
      assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
    verify(statsD).count("scope.close.error", 1L);
    if (manual) {
      verify(statsD).count("scope.user.close.error", 1L);
    }
    verifyNoMoreInteractions(statsD);
  }

  @Test
  void testOnScopeStackOverflow() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(1);
    try (TracerHealthMetrics healthMetrics =
        new TracerHealthMetrics(new Latched(statsD, latch), 100, TimeUnit.MILLISECONDS)) {
      healthMetrics.start();
      healthMetrics.onScopeStackOverflow();
      assertTrue(latch.await(5, TimeUnit.SECONDS));
    }
    verify(statsD).count("scope.error.stack-overflow", 1L);
    verifyNoMoreInteractions(statsD);
  }

  @Test
  void testOnLongRunningUpdate() throws InterruptedException {
    CountDownLatch latch = new CountDownLatch(3);
    try (TracerHealthMetrics healthMetrics =
        new TracerHealthMetrics(new Latched(statsD, latch), 100, TimeUnit.MILLISECONDS)) {
      healthMetrics.start();
      healthMetrics.onLongRunningUpdate(3, 10, 1);
      assertTrue(latch.await(10, TimeUnit.SECONDS));
    }
    verify(statsD).count("long-running.write", 10L);
    verify(statsD).count("long-running.dropped", 3L);
    verify(statsD).count("long-running.expired", 1L);
    verifyNoMoreInteractions(statsD);
  }

  private static class Latched implements StatsDClient {
    private final StatsDClient delegate;
    private final CountDownLatch latch;

    Latched(StatsDClient delegate, CountDownLatch latch) {
      this.delegate = delegate;
      this.latch = latch;
    }

    @Override
    public void incrementCounter(String metricName, String... tags) {
      try {
        delegate.incrementCounter(metricName, tags);
      } finally {
        latch.countDown();
      }
    }

    @Override
    public void count(String metricName, long delta, String... tags) {
      try {
        delegate.count(metricName, delta, tags);
      } finally {
        latch.countDown();
      }
    }

    @Override
    public void gauge(String metricName, long value, String... tags) {
      try {
        delegate.gauge(metricName, value, tags);
      } finally {
        latch.countDown();
      }
    }

    @Override
    public void gauge(String metricName, double value, String... tags) {
      try {
        delegate.gauge(metricName, value, tags);
      } finally {
        latch.countDown();
      }
    }

    @Override
    public void histogram(String metricName, long value, String... tags) {
      try {
        delegate.histogram(metricName, value, tags);
      } finally {
        latch.countDown();
      }
    }

    @Override
    public void histogram(String metricName, double value, String... tags) {
      try {
        delegate.histogram(metricName, value, tags);
      } finally {
        latch.countDown();
      }
    }

    @Override
    public void distribution(String metricName, long value, String... tags) {
      try {
        delegate.distribution(metricName, value, tags);
      } finally {
        latch.countDown();
      }
    }

    @Override
    public void distribution(String metricName, double value, String... tags) {
      try {
        delegate.distribution(metricName, value, tags);
      } finally {
        latch.countDown();
      }
    }

    @Override
    public void serviceCheck(
        String serviceCheckName, String status, String message, String... tags) {
      try {
        delegate.serviceCheck(serviceCheckName, status, message, tags);
      } finally {
        latch.countDown();
      }
    }

    @Override
    public void error(Exception error) {
      try {
        delegate.error(error);
      } finally {
        latch.countDown();
      }
    }

    @Override
    public int getErrorCount() {
      try {
        return delegate.getErrorCount();
      } finally {
        latch.countDown();
      }
    }

    @Override
    public void close() {
      try {
        delegate.close();
      } finally {
        latch.countDown();
      }
    }
  }
}
