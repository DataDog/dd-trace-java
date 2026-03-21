package datadog.trace.core.monitor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import datadog.metrics.api.statsd.StatsDClient;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.common.writer.RemoteApi;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HealthMetricsTest {

  @Mock StatsDClient statsD;

  @Disabled(
      "This fails because RemoteWriter isn't an interface and the mock doesn't prevent the call.")
  @Test
  void testOnStart() {
    // no-op: intentionally disabled
  }

  @Test
  void testOnShutdown() {
    TracerHealthMetrics healthMetrics = new TracerHealthMetrics(statsD);
    healthMetrics.onShutdown(true);
    verifyNoMoreInteractions(statsD);
  }

  static Stream<Arguments> testOnPublishArguments() {
    return Stream.of(
        Arguments.of(Collections.emptyList(), (int) PrioritySampling.USER_DROP, "user_drop"),
        Arguments.of(
            Arrays.asList((Object) null, (Object) null),
            (int) PrioritySampling.USER_DROP,
            "user_drop"),
        Arguments.of(Collections.emptyList(), (int) PrioritySampling.SAMPLER_KEEP, "sampler_keep"),
        Arguments.of(
            Arrays.asList((Object) null, (Object) null),
            (int) PrioritySampling.SAMPLER_KEEP,
            "sampler_keep"));
  }

  @ParameterizedTest
  @MethodSource("testOnPublishArguments")
  void testOnPublish(List trace, int samplingPriority, String priorityName) throws Exception {
    CountDownLatch latch = new CountDownLatch(trace.isEmpty() ? 1 : 2);
    TracerHealthMetrics healthMetrics =
        new TracerHealthMetrics(new Latched(statsD, latch), 100, TimeUnit.MILLISECONDS);
    healthMetrics.start();

    healthMetrics.onPublish(trace, samplingPriority);
    latch.await(10, TimeUnit.SECONDS);

    verify(statsD).count("queue.enqueued.traces", 1, "priority:" + priorityName);
    if (!trace.isEmpty()) {
      verify(statsD).count("queue.enqueued.spans", trace.size());
    }
    verifyNoMoreInteractions(statsD);
    healthMetrics.close();
  }

  static Stream<Arguments> testOnFailedPublishArguments() {
    return Stream.of(
        Arguments.of((int) PrioritySampling.SAMPLER_KEEP, "priority:sampler_keep", 1),
        Arguments.of((int) PrioritySampling.USER_KEEP, "priority:user_keep", 1),
        Arguments.of((int) PrioritySampling.USER_DROP, "priority:user_drop", 1),
        Arguments.of((int) PrioritySampling.SAMPLER_DROP, "priority:sampler_drop", 1),
        Arguments.of((int) PrioritySampling.UNSET, "priority:unset", 1));
  }

  @ParameterizedTest
  @MethodSource("testOnFailedPublishArguments")
  void testOnFailedPublish(int samplingPriority, String samplingTag, int spanCount)
      throws Exception {
    CountDownLatch latch = new CountDownLatch(2);
    TracerHealthMetrics healthMetrics =
        new TracerHealthMetrics(new Latched(statsD, latch), 100, TimeUnit.MILLISECONDS);
    healthMetrics.start();

    healthMetrics.onFailedPublish(samplingPriority, spanCount);
    latch.await(2, TimeUnit.SECONDS);

    verify(statsD).count("queue.dropped.traces", 1, samplingTag);
    verify(statsD).count("queue.dropped.spans", 1, samplingTag);
    verifyNoMoreInteractions(statsD);
    healthMetrics.close();
  }

  static Stream<Arguments> testOnPartialPublishArguments() {
    return Stream.of(
        Arguments.of(1, 4, new String[] {"priority:sampler_drop"}),
        Arguments.of(42, 1, new String[] {"priority:sampler_drop"}),
        Arguments.of(3, 5, new String[] {"priority:sampler_drop"}));
  }

  @ParameterizedTest
  @MethodSource("testOnPartialPublishArguments")
  void testOnPartialPublish(int droppedSpans, int traces, String[] samplingPriority)
      throws Exception {
    CountDownLatch latch = new CountDownLatch(2);
    TracerHealthMetrics healthMetrics =
        new TracerHealthMetrics(new Latched(statsD, latch), 100, TimeUnit.MILLISECONDS);
    healthMetrics.start();

    healthMetrics.onPartialPublish(droppedSpans);
    latch.await(10, TimeUnit.SECONDS);

    verify(statsD).count("queue.partial.traces", 1);
    verify(statsD).count("queue.dropped.spans", droppedSpans, samplingPriority);
    verifyNoMoreInteractions(statsD);
    healthMetrics.close();
  }

  @Test
  void testOnScheduleFlush() {
    TracerHealthMetrics healthMetrics = new TracerHealthMetrics(statsD);
    healthMetrics.onScheduleFlush(true);
    verifyNoMoreInteractions(statsD);
  }

  @Test
  void testOnFlush() {
    TracerHealthMetrics healthMetrics = new TracerHealthMetrics(statsD);
    healthMetrics.onFlush(true);
    verifyNoMoreInteractions(statsD);
  }

  @Test
  void testOnSerialize() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    TracerHealthMetrics healthMetrics =
        new TracerHealthMetrics(new Latched(statsD, latch), 100, TimeUnit.MILLISECONDS);
    int bytes = ThreadLocalRandom.current().nextInt(10000);
    healthMetrics.start();

    healthMetrics.onSerialize(bytes);
    latch.await(10, TimeUnit.SECONDS);

    verify(statsD).count("queue.enqueued.bytes", bytes);
    verifyNoMoreInteractions(statsD);
    healthMetrics.close();
  }

  @Test
  void testOnFailedSerialize() {
    TracerHealthMetrics healthMetrics = new TracerHealthMetrics(statsD);
    healthMetrics.onFailedSerialize(null, null);
    verifyNoMoreInteractions(statsD);
  }

  static Stream<Arguments> testOnSendArguments() {
    return Stream.of(
        Arguments.of(RemoteApi.Response.success(ThreadLocalRandom.current().nextInt(1, 100))),
        Arguments.of(RemoteApi.Response.failed(ThreadLocalRandom.current().nextInt(1, 100))),
        Arguments.of(
            RemoteApi.Response.success(
                ThreadLocalRandom.current().nextInt(1, 100), new Throwable())),
        Arguments.of(RemoteApi.Response.failed(new Throwable())));
  }

  @ParameterizedTest
  @MethodSource("testOnSendArguments")
  void testOnSend(RemoteApi.Response response) throws Exception {
    int expectedLatchCount =
        3 + (response.exception().isPresent() ? 1 : 0) + (response.status().isPresent() ? 1 : 0);
    CountDownLatch latch = new CountDownLatch(expectedLatchCount);
    TracerHealthMetrics healthMetrics =
        new TracerHealthMetrics(new Latched(statsD, latch), 100, TimeUnit.MILLISECONDS);
    healthMetrics.start();

    int traceCount = ThreadLocalRandom.current().nextInt(1, 100);
    int sendSize = ThreadLocalRandom.current().nextInt(1, 100);

    healthMetrics.onSend(traceCount, sendSize, response);
    latch.await(10, TimeUnit.SECONDS);

    verify(statsD).count("api.requests.total", 1);
    verify(statsD).count("flush.traces.total", traceCount);
    verify(statsD).count("flush.bytes.total", sendSize);
    if (response.exception().isPresent()) {
      verify(statsD).count("api.errors.total", 1);
    }
    if (response.status().isPresent()) {
      verify(statsD)
          .incrementCounter("api.responses.total", "status:" + response.status().getAsInt());
    }
    verifyNoMoreInteractions(statsD);
    healthMetrics.close();
  }

  @ParameterizedTest
  @MethodSource("testOnSendArguments")
  void testOnFailedSend(RemoteApi.Response response) throws Exception {
    int expectedLatchCount =
        3 + (response.exception().isPresent() ? 1 : 0) + (response.status().isPresent() ? 1 : 0);
    CountDownLatch latch = new CountDownLatch(expectedLatchCount);
    TracerHealthMetrics healthMetrics =
        new TracerHealthMetrics(new Latched(statsD, latch), 100, TimeUnit.MILLISECONDS);
    healthMetrics.start();

    int traceCount = ThreadLocalRandom.current().nextInt(1, 100);
    int sendSize = ThreadLocalRandom.current().nextInt(1, 100);

    healthMetrics.onFailedSend(traceCount, sendSize, response);
    latch.await(10, TimeUnit.SECONDS);

    verify(statsD).count("api.requests.total", 1);
    verify(statsD).count("flush.traces.total", traceCount);
    verify(statsD).count("flush.bytes.total", sendSize);
    if (response.exception().isPresent()) {
      verify(statsD).count("api.errors.total", 1);
    }
    if (response.status().isPresent()) {
      verify(statsD)
          .incrementCounter("api.responses.total", "status:" + response.status().getAsInt());
    }
    verifyNoMoreInteractions(statsD);
    healthMetrics.close();
  }

  @Test
  void testOnCreateTrace() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    TracerHealthMetrics healthMetrics =
        new TracerHealthMetrics(new Latched(statsD, latch), 100, TimeUnit.MILLISECONDS);
    healthMetrics.start();
    healthMetrics.onCreateTrace();
    latch.await(5, TimeUnit.SECONDS);
    verify(statsD).count(eq("trace.pending.created"), eq(1L), any(String[].class));
    healthMetrics.close();
  }

  @Test
  void testOnCreateSpan() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    TracerHealthMetrics healthMetrics =
        new TracerHealthMetrics(new Latched(statsD, latch), 100, TimeUnit.MILLISECONDS);
    healthMetrics.start();
    healthMetrics.onCreateSpan();
    latch.await(5, TimeUnit.SECONDS);
    verify(statsD).count(eq("span.pending.created"), eq(1L), any(String[].class));
    healthMetrics.close();
  }

  @Test
  void testOnCancelContinuation() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    TracerHealthMetrics healthMetrics =
        new TracerHealthMetrics(new Latched(statsD, latch), 100, TimeUnit.MILLISECONDS);
    healthMetrics.start();
    healthMetrics.onCancelContinuation();
    latch.await(5, TimeUnit.SECONDS);
    verify(statsD).count(eq("span.continuations.canceled"), eq(1L), any(String[].class));
    healthMetrics.close();
  }

  @Test
  void testOnFinishContinuation() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    TracerHealthMetrics healthMetrics =
        new TracerHealthMetrics(new Latched(statsD, latch), 100, TimeUnit.MILLISECONDS);
    healthMetrics.start();
    healthMetrics.onFinishContinuation();
    latch.await(5, TimeUnit.SECONDS);
    verify(statsD).count(eq("span.continuations.finished"), eq(1L), any(String[].class));
    healthMetrics.close();
  }

  @Test
  void testOnSingleSpanSample() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    TracerHealthMetrics healthMetrics =
        new TracerHealthMetrics(new Latched(statsD, latch), 100, TimeUnit.MILLISECONDS);
    healthMetrics.start();
    healthMetrics.onSingleSpanSample();
    latch.await(5, TimeUnit.SECONDS);
    verify(statsD).count(eq("span.sampling.sampled"), eq(1L), eq("sampler:single-span"));
    healthMetrics.close();
  }

  @Test
  void testOnSingleSpanUnsampled() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    TracerHealthMetrics healthMetrics =
        new TracerHealthMetrics(new Latched(statsD, latch), 100, TimeUnit.MILLISECONDS);
    healthMetrics.start();
    healthMetrics.onSingleSpanUnsampled();
    latch.await(5, TimeUnit.SECONDS);
    verify(statsD).count(eq("span.sampling.unsampled"), eq(1L), eq("sampler:single-span"));
    healthMetrics.close();
  }

  @Test
  void testOnFinishSpan() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    TracerHealthMetrics healthMetrics =
        new TracerHealthMetrics(new Latched(statsD, latch), 100, TimeUnit.MILLISECONDS);
    healthMetrics.start();
    healthMetrics.onFinishSpan();
    latch.await(5, TimeUnit.SECONDS);
    verify(statsD).count(eq("span.pending.finished"), eq(1L), any(String[].class));
    healthMetrics.close();
  }

  @Test
  void testOnActivateScope() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    TracerHealthMetrics healthMetrics =
        new TracerHealthMetrics(new Latched(statsD, latch), 100, TimeUnit.MILLISECONDS);
    healthMetrics.start();
    healthMetrics.onActivateScope();
    latch.await(5, TimeUnit.SECONDS);
    verify(statsD).count(eq("scope.activate.count"), eq(1L), any(String[].class));
    healthMetrics.close();
  }

  @Test
  void testOnCloseScope() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    TracerHealthMetrics healthMetrics =
        new TracerHealthMetrics(new Latched(statsD, latch), 100, TimeUnit.MILLISECONDS);
    healthMetrics.start();
    healthMetrics.onCloseScope();
    latch.await(5, TimeUnit.SECONDS);
    verify(statsD).count(eq("scope.close.count"), eq(1L), any(String[].class));
    healthMetrics.close();
  }

  static Stream<Arguments> testOnScopeCloseErrorArguments() {
    return Stream.of(Arguments.of(false), Arguments.of(true));
  }

  @ParameterizedTest
  @MethodSource("testOnScopeCloseErrorArguments")
  void testOnScopeCloseError(boolean manual) throws Exception {
    CountDownLatch latch = new CountDownLatch(1 + (manual ? 1 : 0));
    TracerHealthMetrics healthMetrics =
        new TracerHealthMetrics(new Latched(statsD, latch), 100, TimeUnit.MILLISECONDS);
    healthMetrics.start();
    healthMetrics.onScopeCloseError(manual);
    latch.await(5, TimeUnit.SECONDS);
    verify(statsD).count(eq("scope.close.error"), eq(1L), any(String[].class));
    if (manual) {
      verify(statsD).count(eq("scope.user.close.error"), eq(1L), any(String[].class));
    }
    healthMetrics.close();
  }

  @Test
  void testOnScopeStackOverflow() throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    TracerHealthMetrics healthMetrics =
        new TracerHealthMetrics(new Latched(statsD, latch), 100, TimeUnit.MILLISECONDS);
    healthMetrics.start();
    healthMetrics.onScopeStackOverflow();
    latch.await(5, TimeUnit.SECONDS);
    verify(statsD).count(eq("scope.error.stack-overflow"), eq(1L), any(String[].class));
    healthMetrics.close();
  }

  @Test
  void testOnLongRunningUpdate() throws Exception {
    CountDownLatch latch = new CountDownLatch(3);
    TracerHealthMetrics healthMetrics =
        new TracerHealthMetrics(new Latched(statsD, latch), 100, TimeUnit.MILLISECONDS);
    healthMetrics.start();
    healthMetrics.onLongRunningUpdate(3, 10, 1);
    latch.await(10, TimeUnit.SECONDS);
    verify(statsD).count(eq("long-running.write"), eq(10L), any(String[].class));
    verify(statsD).count(eq("long-running.dropped"), eq(3L), any(String[].class));
    verify(statsD).count(eq("long-running.expired"), eq(1L), any(String[].class));
    healthMetrics.close();
  }

  private static class Latched implements StatsDClient {
    final StatsDClient delegate;
    final CountDownLatch latch;

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
