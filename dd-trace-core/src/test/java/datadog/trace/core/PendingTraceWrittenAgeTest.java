package datadog.trace.core;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.time.ControllableTimeSource;
import datadog.trace.common.writer.ListWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The time since a trace was handed to the writer, which the writer uses to evict stale traces. */
class PendingTraceWrittenAgeTest extends DDCoreJavaSpecification {

  private final ControllableTimeSource time = new ControllableTimeSource();
  private final ListWriter writer = new ListWriter();
  private CoreTracer tracer;

  @AfterEach
  void cleanup() {
    if (tracer != null) {
      tracer.close();
    }
  }

  @Test
  void ageCountsFromTheWriteNotFromTheLastSpanActivity() throws Exception {
    time.set(MILLISECONDS.toNanos(1_000));
    tracer = tracerBuilder().writer(writer).timeSource(time).build();
    DDSpan root = (DDSpan) tracer.buildSpan("datadog", "root").start();
    PendingTrace trace = (PendingTrace) root.spanContext().getTraceCollector();

    // a trace that hasn't been written has no age, however long it has been idle
    time.advance(MILLISECONDS.toNanos(10_000));
    assertFalse(trace.writtenNanosAgo(MILLISECONDS.toNanos(250)));

    root.finish();
    writer.waitForTraces(1);
    assertFalse(trace.writtenNanosAgo(MILLISECONDS.toNanos(250)));

    time.advance(MILLISECONDS.toNanos(300));
    assertTrue(trace.writtenNanosAgo(MILLISECONDS.toNanos(250)));
  }
}
