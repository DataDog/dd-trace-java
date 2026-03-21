package datadog.trace.core;

import static datadog.trace.api.sampling.PrioritySampling.UNSET;
import static datadog.trace.api.sampling.PrioritySampling.USER_KEEP;
import static datadog.trace.core.PendingTraceBuffer.BUFFER_SIZE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import datadog.environment.JavaVirtualMachine;
import datadog.metrics.api.Monitoring;
import datadog.trace.SamplingPriorityMetadataChecker;
import datadog.trace.api.Config;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.datastreams.NoopPathwayContext;
import datadog.trace.api.flare.TracerFlare;
import datadog.trace.api.time.SystemTimeSource;
import datadog.trace.context.TraceScope;
import datadog.trace.core.monitor.HealthMetrics;
import datadog.trace.core.propagation.PropagationTags;
import datadog.trace.core.scopemanager.ContinuableScopeManager;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledIf;

@DisabledIf("isOracleJdk8")
@Timeout(5)
public class PendingTraceBufferTest {

  static boolean isOracleJdk8() {
    return JavaVirtualMachine.isOracleJDK8();
  }

  PendingTraceBuffer.DelayingPendingTraceBuffer buffer;
  PendingTraceBuffer bufferSpy;
  CoreTracer tracer;
  CoreTracer.ConfigSnapshot traceConfig;
  ContinuableScopeManager scopeManager;
  PendingTrace.Factory factory;
  List<TraceScope.Continuation> continuations;

  @BeforeEach
  void setup() {
    buffer =
        (PendingTraceBuffer.DelayingPendingTraceBuffer)
            PendingTraceBuffer.delaying(SystemTimeSource.INSTANCE, mock(Config.class), null, null);
    bufferSpy = spy(buffer);
    tracer = mock(CoreTracer.class);
    traceConfig = mock(CoreTracer.ConfigSnapshot.class);
    scopeManager = new ContinuableScopeManager(10, true);
    factory =
        new PendingTrace.Factory(
            tracer, bufferSpy, SystemTimeSource.INSTANCE, false, HealthMetrics.NO_OP);
    continuations = new ArrayList<>();
    when(tracer.captureTraceConfig()).thenReturn(traceConfig);
    when(traceConfig.getServiceMapping()).thenReturn(Collections.emptyMap());
  }

  @AfterEach
  void cleanup() throws Exception {
    buffer.close();
    buffer.worker.join(1000);
  }

  @Test
  void testBufferLifecycle() throws Exception {
    assertFalse(buffer.worker.isAlive());

    buffer.start();

    assertTrue(buffer.worker.isAlive());
    assertTrue(buffer.worker.isDaemon());

    assertThrows(IllegalThreadStateException.class, () -> buffer.start());
    assertTrue(buffer.worker.isAlive());
    assertTrue(buffer.worker.isDaemon());

    buffer.close();
    buffer.worker.join(1000);

    assertFalse(buffer.worker.isAlive());
  }

  @Test
  void continuationBuffersRoot() throws Exception {
    PendingTrace trace = factory.create(DDTraceId.ONE);
    DDSpan span = newSpanOf(trace);

    assertFalse(trace.isRootSpanWritten());

    addContinuation(span);
    span.finish(); // This should enqueue

    assertEquals(1, continuations.size());
    assertEquals(1, trace.getPendingReferenceCount());
    verify(bufferSpy, times(1)).enqueue(trace);
    verify(tracer, times(1)).onRootSpanPublished(span);

    continuations.get(0).cancel();

    assertEquals(0, trace.getPendingReferenceCount());
    verify(tracer, times(1)).write(any());
    verify(tracer, times(1)).writeTimer();
  }

  @Test
  void unfinishedChildBuffersRoot() throws Exception {
    PendingTrace trace = factory.create(DDTraceId.ONE);
    DDSpan parent = newSpanOf(trace);
    DDSpan child = newSpanOf(parent);

    assertFalse(trace.isRootSpanWritten());

    parent.finish(); // This should enqueue

    assertEquals(1, trace.size());
    assertEquals(1, trace.getPendingReferenceCount());
    verify(bufferSpy, times(1)).enqueue(trace);
    verify(tracer, times(1)).onRootSpanPublished(parent);

    child.finish();

    assertEquals(0, trace.size());
    assertEquals(0, trace.getPendingReferenceCount());
    verify(tracer, times(1)).write(any());
    verify(tracer, times(1)).writeTimer();
  }

  @Test
  void prioritySamplingIsAlwaysSent() throws Exception {
    DDSpan parent = addContinuation(newSpanOf(factory.create(DDTraceId.ONE), USER_KEEP, 0));
    SamplingPriorityMetadataChecker metadataChecker = new SamplingPriorityMetadataChecker();
    when(tracer.getPartialFlushMinSpans()).thenReturn(10);
    doAnswer(
            invocation -> {
              SpanList spans = invocation.getArgument(0);
              spans.get(0).processTagsAndBaggage(metadataChecker);
              return null;
            })
        .when(tracer)
        .write(any());

    // Fill the buffer - Only children - Priority taken from root
    for (int i = 0; i < 11; i++) {
      newSpanOf(parent).finish();
    }

    verify(tracer, times(1)).write(any());
    assertTrue(metadataChecker.hasSamplingPriority);
  }

  @Test
  void bufferFullYieldsImmediateWrite() throws Exception {
    // Don't start the buffer thread
    when(tracer.getPartialFlushMinSpans()).thenReturn(10);

    // Fill the buffer
    for (int i = 0; i < buffer.queue.capacity(); i++) {
      addContinuation(newSpanOf(factory.create(DDTraceId.ONE))).finish();
    }

    assertEquals(BUFFER_SIZE, buffer.queue.size());
    verify(bufferSpy, times(buffer.queue.capacity())).enqueue(any());

    PendingTrace pendingTrace = factory.create(DDTraceId.ONE);
    addContinuation(newSpanOf(pendingTrace)).finish();

    verify(bufferSpy, atLeastOnce()).enqueue(any());
    verify(tracer, times(1)).write(any());
    verify(tracer, times(1)).writeTimer();
    assertEquals(0, pendingTrace.getIsEnqueued());
  }

  @Test
  void longRunningTraceBufferFullDoesNotTriggerWrite() throws Exception {
    // Don't start the buffer thread
    when(tracer.getPartialFlushMinSpans()).thenReturn(10);

    // Fill the buffer
    for (int i = 0; i < buffer.queue.capacity(); i++) {
      addContinuation(newSpanOf(factory.create(DDTraceId.ONE))).finish();
    }

    assertEquals(BUFFER_SIZE, buffer.queue.size());

    PendingTrace pendingTrace = factory.create(DDTraceId.ONE);
    pendingTrace.setLongRunningTrackedState(LongRunningTracesTracker.TO_TRACK);
    addContinuation(newSpanOf(pendingTrace)).finish();

    // No write should occur for long-running traces when buffer is full
    verify(tracer, times(0)).write(any());
    assertEquals(0, pendingTrace.getIsEnqueued());
  }

  @Test
  void continuationAllowsAddingAfterRootFinished() throws Exception {
    PendingTrace trace = factory.create(DDTraceId.ONE);
    DDSpan parent = addContinuation(newSpanOf(trace));
    TraceScope.Continuation continuation = continuations.get(0);

    assertEquals(1, continuations.size());

    parent.finish(); // This should enqueue

    assertEquals(1, trace.size());
    assertEquals(1, trace.getPendingReferenceCount());
    assertFalse(trace.isRootSpanWritten());
    verify(bufferSpy, times(1)).enqueue(trace);
    verify(tracer, times(1)).onRootSpanPublished(parent);

    DDSpan child = newSpanOf(parent);
    child.finish();

    assertEquals(2, trace.size());
    assertEquals(1, trace.getPendingReferenceCount());
    assertFalse(trace.isRootSpanWritten());

    // Don't start the buffer thread here. When the continuation is cancelled,
    // pendingReferenceCount drops to 0 with rootSpanWritten still false, so
    // write() is called synchronously on this thread.
    continuation.cancel();

    assertEquals(0, trace.size());
    assertEquals(0, trace.getPendingReferenceCount());
    assertTrue(trace.isRootSpanWritten());
    verify(tracer, times(1)).writeTimer();
    verify(tracer, times(1)).write(any());
  }

  @Test
  void lateArrivalSpanRequeuesPendingTrace() throws Exception {
    buffer.start();
    CountDownLatch parentLatch = new CountDownLatch(1);
    CountDownLatch childLatch = new CountDownLatch(1);

    PendingTrace trace = factory.create(DDTraceId.ONE);
    DDSpan parent = newSpanOf(trace);

    when(tracer.writeTimer()).thenReturn(Monitoring.DISABLED.newTimer(""));
    doAnswer(
            invocation -> {
              parentLatch.countDown();
              return null;
            })
        .when(tracer)
        .write(any());

    parent.finish(); // This should enqueue
    parentLatch.await();

    assertEquals(0, trace.size());
    assertEquals(0, trace.getPendingReferenceCount());
    assertTrue(trace.isRootSpanWritten());

    // Reset mock for next write call
    doAnswer(
            invocation -> {
              childLatch.countDown();
              return null;
            })
        .when(tracer)
        .write(any());

    DDSpan child = newSpanOf(parent);
    child.finish();
    childLatch.await();

    assertEquals(0, trace.size());
    assertEquals(0, trace.getPendingReferenceCount());
    assertTrue(trace.isRootSpanWritten());
    verify(bufferSpy, atLeastOnce()).enqueue(trace);
  }

  @Test
  void flushClearsTheBuffer() throws Exception {
    buffer.start();
    AtomicInteger counter = new AtomicInteger(0);
    PendingTraceBuffer.Element element =
        new PendingTraceBuffer.Element() {
          @Override
          public long oldestFinishedTime() {
            return TimeUnit.MILLISECONDS.toNanos(System.currentTimeMillis());
          }

          @Override
          public boolean lastReferencedNanosAgo(long nanos) {
            return false;
          }

          @Override
          public void write() {
            counter.incrementAndGet();
          }

          @Override
          public DDSpan getRootSpan() {
            return null;
          }

          @Override
          public boolean setEnqueued(boolean enqueued) {
            return true;
          }

          @Override
          public boolean writeOnBufferFull() {
            return true;
          }
        };

    buffer.enqueue(element);
    buffer.enqueue(element);
    buffer.enqueue(element);

    assertEquals(0, counter.get());

    buffer.flush();

    assertEquals(3, counter.get());
  }

  @Test
  void theSamePendingTraceIsNotEnqueuedMultipleTimes() throws Exception {
    // Don't start the buffer thread
    when(tracer.getPartialFlushMinSpans()).thenReturn(10000);

    // finish the root span
    PendingTrace pendingTrace = factory.create(DDTraceId.ONE);
    DDSpan span = newSpanOf(pendingTrace);
    span.finish();

    assertTrue(pendingTrace.isRootSpanWritten());
    assertEquals(0, pendingTrace.getIsEnqueued());
    assertEquals(0, buffer.queue.size());

    // fail to fill the buffer
    for (int i = 0; i < buffer.queue.capacity(); i++) {
      addContinuation(newSpanOf(span)).finish();
    }

    assertEquals(1, pendingTrace.getIsEnqueued());
    assertEquals(1, buffer.queue.size());

    // process the buffer
    buffer.start();

    long deadline = System.currentTimeMillis() + 3000;
    while (pendingTrace.getIsEnqueued() != 0 && System.currentTimeMillis() < deadline) {
      Thread.sleep(100);
    }
    assertEquals(0, pendingTrace.getIsEnqueued());
  }

  DDSpan addContinuation(DDSpan span) {
    TraceScope scope = scopeManager.activateSpan(span);
    continuations.add(scopeManager.captureSpan(span));
    scope.close();
    return span;
  }

  static DDSpan newSpanOf(PendingTrace trace) {
    return newSpanOf(trace, UNSET, 0);
  }

  static DDSpan newSpanOf(PendingTrace trace, int samplingPriority, long timestampMicro) {
    DDSpanContext context =
        new DDSpanContext(
            trace.getTraceId(),
            1,
            DDSpanId.ZERO,
            null,
            "fakeService",
            "fakeOperation",
            "fakeResource",
            samplingPriority,
            null,
            Collections.emptyMap(),
            false,
            "fakeType",
            0,
            trace,
            null,
            null,
            NoopPathwayContext.INSTANCE,
            false,
            PropagationTags.factory().empty());
    return DDSpan.create("test", timestampMicro, context, null);
  }

  static DDSpan newSpanOf(DDSpan parent) {
    PendingTrace traceCollector = (PendingTrace) parent.context().getTraceCollector();
    DDSpanContext context =
        new DDSpanContext(
            traceCollector.getTraceId(),
            2,
            parent.context().getSpanId(),
            null,
            "fakeService",
            "fakeOperation",
            "fakeResource",
            UNSET,
            null,
            Collections.emptyMap(),
            false,
            "fakeType",
            0,
            traceCollector,
            null,
            null,
            NoopPathwayContext.INSTANCE,
            false,
            PropagationTags.factory().empty());
    return DDSpan.create("test", 0, context, null);
  }

  Map<String, Object> buildAndExtractZip() throws Exception {
    TracerFlare.prepareForFlare();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(out)) {
      TracerFlare.addReportsToFlare(zip);
    } finally {
      TracerFlare.cleanupAfterFlare();
    }

    Map<String, Object> entries = new HashMap<>();
    ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()));
    ZipEntry entry;
    while ((entry = zip.getNextEntry()) != null) {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      byte[] buf = new byte[4096];
      int n;
      while ((n = zip.read(buf)) != -1) {
        bytes.write(buf, 0, n);
      }
      String name = entry.getName();
      if (name.endsWith(".bin")) {
        entries.put(name, bytes.toByteArray());
      } else {
        entries.put(name, new String(bytes.toByteArray(), UTF_8));
      }
    }
    return entries;
  }
}
