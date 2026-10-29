package com.datadog.openfeature.internal.exposure;

import static java.util.Collections.emptyMap;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.datadog.openfeature.internal.RuntimeServices;
import com.datadog.openfeature.internal.bridge.v1.EventTransport;
import com.datadog.openfeature.internal.bridge.v1.HealthMetrics;
import com.datadog.openfeature.testlog.CapturingServiceProvider;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.event.Level;

class ExposurePipelineDiagnosticsTest {
  private static final long AWAIT_MILLIS = SECONDS.toMillis(5);

  @BeforeEach
  void captureLogs() {
    CapturingServiceProvider.reset();
    CapturingServiceProvider.enable("com.datadog.openfeature", Level.DEBUG);
  }

  @AfterEach
  void restore() {
    CapturingServiceProvider.reset();
  }

  @Test
  void reportsTheExposuresDroppedByAFullQueueOncePerFlush() throws Exception {
    final ExposurePipeline pipeline = pipeline(1, (route, json) -> {});
    final int logged = pipelineEvents().size();

    pipeline.accept(exposure());
    pipeline.accept(exposure());
    pipeline.accept(exposure());

    // The producer, an application thread, does not log.
    assertEquals(logged, pipelineEvents().size(), messages("").toString());
    pipeline.start();
    try {
      awaitLog("exposures dropped since the last flush: queue full, count=2");
    } finally {
      pipeline.close();
    }
    assertEquals(1, messages("queue full").size(), messages("").toString());
  }

  @Test
  void reportsTheShutdownRequestWithoutLoggingEachExposure() {
    final ExposurePipeline pipeline = pipeline(16, (route, json) -> {});
    final int logged = pipelineEvents().size();

    pipeline.accept(exposure());
    pipeline.requestShutdown();
    pipeline.accept(exposure());

    assertEquals(logged + 1, pipelineEvents().size(), messages("").toString());
    assertEquals(1, messages("exposure shutdown requested: queued=1").size());
  }

  @Test
  void signalsTheWorkerWhenTheShutdownLogFails() {
    final ExposurePipeline pipeline = pipeline(16, (route, json) -> {});
    pipeline.start();
    CapturingServiceProvider.failOn("exposure shutdown requested");

    assertThrows(IllegalStateException.class, pipeline::requestShutdown);

    pipeline.awaitTermination(System.nanoTime() + SECONDS.toNanos(5));
    assertFalse(pipeline.isSerializerThreadAlive(), "The worker was not signaled");
  }

  @Test
  void reportsADeliveredFlush() throws Exception {
    final ExposurePipeline pipeline = pipeline(16, (route, json) -> {});
    pipeline.start();
    try {
      pipeline.accept(exposure());

      awaitLog("exposure flush delivered: count=1");
    } finally {
      pipeline.close();
    }

    assertFalse(messages("exposure flush starting: count=1").isEmpty());
  }

  @Test
  void reportsAFailedFlushAsDroppingTheBatch() throws Exception {
    final ExposurePipeline pipeline =
        pipeline(
            16,
            (route, json) -> {
              throw new IOException("returned error response 503");
            });
    pipeline.start();
    try {
      pipeline.accept(exposure());

      awaitLog("exposure flush failed: java.io.IOException: returned error response 503");
    } finally {
      pipeline.close();
    }

    assertFalse(messages("dropping the batch of 1 exposure(s)").isEmpty());
  }

  private static ExposurePipeline pipeline(final int capacity, final EventTransport transport) {
    return new ExposurePipeline(
        capacity, 10, MILLISECONDS, transport, emptyMap(), new RuntimeServices(HealthMetrics.NOOP));
  }

  private static ExposureEvent exposure() {
    return new ExposureEvent(
        1L,
        new Allocation("allocation"),
        new Flag("flag"),
        new Variant("variant"),
        new Subject("subject", emptyMap()),
        1);
  }

  private static void awaitLog(final String text) throws InterruptedException {
    final long deadline = System.currentTimeMillis() + AWAIT_MILLIS;
    while (messages(text).isEmpty() && System.currentTimeMillis() < deadline) {
      Thread.sleep(10);
    }
    assertFalse(messages(text).isEmpty(), "Missing log: " + text + " in " + messages(""));
  }

  /** The events of the pipeline only: other threads of the JVM can log in the same window. */
  private static List<CapturingServiceProvider.Event> pipelineEvents() {
    return CapturingServiceProvider.events().stream()
        .filter(e -> e.logger.equals(ExposurePipeline.class.getName()))
        .collect(Collectors.toList());
  }

  private static List<String> messages(final String text) {
    return CapturingServiceProvider.events().stream()
        .map(e -> e.message)
        .filter(message -> message.contains(text))
        .collect(Collectors.toList());
  }
}
