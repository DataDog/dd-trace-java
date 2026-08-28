package datadog.trace.core;

import static datadog.trace.api.sampling.PrioritySampling.UNSET;
import static datadog.trace.api.sampling.PrioritySampling.USER_KEEP;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import datadog.context.ContextContinuation;
import datadog.context.ContextScope;
import datadog.environment.JavaVirtualMachine;
import datadog.metrics.api.Monitoring;
import datadog.trace.SamplingPriorityMetadataChecker;
import datadog.trace.api.Config;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.datastreams.NoopPathwayContext;
import datadog.trace.api.flare.TracerFlare;
import datadog.trace.api.time.SystemTimeSource;
import datadog.trace.core.monitor.HealthMetrics;
import datadog.trace.core.propagation.PropagationTags;
import datadog.trace.core.scopemanager.ContinuableScopeManager;
import datadog.trace.test.util.DDJavaSpecification;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
public class PendingTraceBufferTest extends DDJavaSpecification {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private PendingTraceBuffer buffer;
  private PendingTraceBuffer bufferSpy;
  private PendingTraceBuffer.DelayingPendingTraceBuffer delayingBuffer;
  private CoreTracer tracer;
  private CoreTracer.ConfigSnapshot traceConfig;
  private ContinuableScopeManager scopeManager;
  private PendingTrace.Factory factory;
  private List<ContextContinuation> continuations;

  @BeforeAll
  static void checkJvm() {
    Assumptions.assumeFalse(
        JavaVirtualMachine.isOracleJDK8(),
        "Oracle JDK 1.8 did not merge the fix in JDK-8058322, leading to the JVM failing to"
            + " correctly extract method parameters without args, when the code is compiled on a"
            + " later JDK (targeting 8). This can manifest when creating mocks.");
  }

  @BeforeEach
  void setup() {
    tracer = mock(CoreTracer.class);
    traceConfig = mock(CoreTracer.ConfigSnapshot.class);
    buffer = PendingTraceBuffer.delaying(SystemTimeSource.INSTANCE, mock(Config.class), null, null);
    bufferSpy = spy(buffer);
    delayingBuffer = (PendingTraceBuffer.DelayingPendingTraceBuffer) buffer;
    scopeManager = new ContinuableScopeManager(10, true);
    factory =
        new PendingTrace.Factory(
            tracer, bufferSpy, SystemTimeSource.INSTANCE, false, HealthMetrics.NO_OP);
    continuations = new ArrayList<>();

    when(tracer.captureTraceConfig()).thenReturn(traceConfig);
    when(traceConfig.getServiceMapping()).thenReturn(Collections.emptyMap());
    when(tracer.getPartialFlushMinSpans()).thenReturn(10);
    when(tracer.writeTimer()).thenReturn(Monitoring.DISABLED.newTimer(""));
    when(tracer.getTimeWithNanoTicks(anyLong())).thenAnswer(inv -> inv.<Long>getArgument(0));
  }

  @AfterEach
  void cleanup() throws InterruptedException {
    buffer.close();
    delayingBuffer.getWorker().join(1000);
  }

  @Test
  void testBufferLifecycle() throws InterruptedException {
    assertFalse(delayingBuffer.getWorker().isAlive());

    buffer.start();

    assertTrue(delayingBuffer.getWorker().isAlive());
    assertTrue(delayingBuffer.getWorker().isDaemon());

    assertThrows(IllegalThreadStateException.class, () -> buffer.start());
    assertTrue(delayingBuffer.getWorker().isAlive());
    assertTrue(delayingBuffer.getWorker().isDaemon());

    buffer.close();
    delayingBuffer.getWorker().join(1000);

    assertFalse(delayingBuffer.getWorker().isAlive());
  }

  @Test
  void continuationBuffersRoot() {
    PendingTrace trace = factory.create(DDTraceId.ONE);
    DDSpan span = newSpanOf(trace);

    assertFalse(trace.isRootSpanWritten());

    addContinuation(span);
    span.finish(); // This should enqueue

    assertEquals(1, continuations.size());
    assertEquals(1, trace.getPendingReferenceCount());
    verify(bufferSpy).enqueue(trace);
    verify(tracer).onRootSpanPublished(span);

    clearInvocations(bufferSpy, tracer, traceConfig);

    continuations.get(0).release();

    assertEquals(0, trace.getPendingReferenceCount());
    verify(tracer).write(argThat(it -> it.size() == 1));
    verify(tracer).writeTimer();
  }

  @Test
  void unfinishedChildBuffersRoot() {
    PendingTrace trace = factory.create(DDTraceId.ONE);
    DDSpan parent = newSpanOf(trace);
    DDSpan child = newSpanOf(parent);

    assertFalse(trace.isRootSpanWritten());

    parent.finish(); // This should enqueue

    assertEquals(1, trace.size());
    assertEquals(1, trace.getPendingReferenceCount());
    verify(bufferSpy).enqueue(trace);
    verify(tracer).onRootSpanPublished(parent);

    clearInvocations(bufferSpy, tracer);

    child.finish();

    assertEquals(0, trace.size());
    assertEquals(0, trace.getPendingReferenceCount());
    verify(tracer).write(argThat(it -> it.size() == 2));
    verify(tracer).writeTimer();
  }

  @Test
  void prioritySamplingIsAlwaysSent() {
    SamplingPriorityMetadataChecker metadataChecker = new SamplingPriorityMetadataChecker();
    doAnswer(
            invocation -> {
              List<DDSpan> spans = invocation.getArgument(0);
              spans.get(0).processTagsAndBaggage(metadataChecker);
              return null;
            })
        .when(tracer)
        .write(any());

    DDSpan parent = addContinuation(newSpanOf(factory.create(DDTraceId.ONE), USER_KEEP, 0));
    // Fill the buffer - Only children - Priority taken from root
    for (int i = 0; i < 11; i++) {
      newSpanOf(parent).finish();
    }

    verify(tracer).write(any());
    assertTrue(metadataChecker.hasSamplingPriority);
  }

  @Test
  void bufferFullYieldsImmediateWrite() {
    int capacity = delayingBuffer.getQueue().capacity();

    // Fill the buffer
    for (int i = 0; i < capacity; i++) {
      addContinuation(newSpanOf(factory.create(DDTraceId.ONE))).finish();
    }

    assertEquals(capacity, delayingBuffer.getQueue().size());
    verify(bufferSpy, times(capacity)).enqueue(any());
    verify(tracer, times(capacity)).onRootSpanPublished(any());

    clearInvocations(bufferSpy, tracer, traceConfig);

    PendingTrace pendingTrace = factory.create(DDTraceId.ONE);
    addContinuation(newSpanOf(pendingTrace)).finish();

    verify(bufferSpy).enqueue(any());
    verify(tracer).write(argThat(it -> it.size() == 1));
    verify(tracer).onRootSpanPublished(any());
    assertEquals(0, pendingTrace.getIsEnqueued());
  }

  @Test
  void longRunningTraceBufferFullDoesNotTriggerWrite() {
    int capacity = delayingBuffer.getQueue().capacity();

    // Fill the buffer
    for (int i = 0; i < capacity; i++) {
      addContinuation(newSpanOf(factory.create(DDTraceId.ONE))).finish();
    }

    assertEquals(capacity, delayingBuffer.getQueue().size());
    verify(bufferSpy, times(capacity)).enqueue(any());
    verify(tracer, times(capacity)).onRootSpanPublished(any());

    clearInvocations(bufferSpy, tracer, traceConfig);

    PendingTrace pendingTrace = factory.create(DDTraceId.ONE);
    pendingTrace.setLongRunningTrackedState(LongRunningTracesTracker.TO_TRACK);
    addContinuation(newSpanOf(pendingTrace)).finish();

    verify(tracer).captureTraceConfig();
    verify(bufferSpy).enqueue(any());
    verify(tracer, never()).writeTimer();
    verify(tracer, never()).write(argThat(it -> it.size() == 1));
    verify(tracer).onRootSpanPublished(any());
    assertEquals(0, pendingTrace.getIsEnqueued());
  }

  @Test
  void continuationAllowsAddingAfterRootFinished() {
    PendingTrace trace = factory.create(DDTraceId.ONE);
    DDSpan parent = addContinuation(newSpanOf(trace));
    ContextContinuation continuation = continuations.get(0);

    assertEquals(1, continuations.size());

    parent.finish(); // This should enqueue

    assertEquals(1, trace.size());
    assertEquals(1, trace.getPendingReferenceCount());
    assertFalse(trace.isRootSpanWritten());
    verify(bufferSpy).enqueue(trace);
    verify(tracer).onRootSpanPublished(parent);

    clearInvocations(bufferSpy, tracer);

    DDSpan child = newSpanOf(parent);
    child.finish();

    assertEquals(2, trace.size());
    assertEquals(1, trace.getPendingReferenceCount());
    assertFalse(trace.isRootSpanWritten());

    // Don't start the buffer thread here. When the continuation is cancelled,
    // pendingReferenceCount drops to 0 with rootSpanWritten still false, so
    // write() is called synchronously on this thread. Starting the buffer
    // would introduce a race where the worker thread could process the
    // enqueued trace before continuation.cancel(), causing unexpected interactions.
    continuation.release();

    assertEquals(0, trace.size());
    assertEquals(0, trace.getPendingReferenceCount());
    assertTrue(trace.isRootSpanWritten());
    verify(tracer).write(argThat(it -> it.size() == 2));
    verify(tracer).writeTimer();
  }

  @Test
  void lateArrivalSpanRequeuesPendingTrace() throws InterruptedException {
    buffer.start();
    CountDownLatch parentLatch = new CountDownLatch(1);
    CountDownLatch childLatch = new CountDownLatch(1);

    PendingTrace trace = factory.create(DDTraceId.ONE);
    DDSpan parent = newSpanOf(trace);

    doAnswer(
            invocation -> {
              parentLatch.countDown();
              return null;
            })
        .doAnswer(
            invocation -> {
              childLatch.countDown();
              return null;
            })
        .when(tracer)
        .write(any());

    parent.finish(); // This should enqueue
    assertTrue(parentLatch.await(3, TimeUnit.SECONDS));

    assertEquals(0, trace.size());
    assertEquals(0, trace.getPendingReferenceCount());
    assertTrue(trace.isRootSpanWritten());
    verify(tracer).write(argThat(it -> it.size() == 1));
    verify(tracer).onRootSpanPublished(parent);

    clearInvocations(bufferSpy, tracer, traceConfig);

    DDSpan child = newSpanOf(parent);
    child.finish();
    assertTrue(childLatch.await(3, TimeUnit.SECONDS));

    assertEquals(0, trace.size());
    assertEquals(0, trace.getPendingReferenceCount());
    assertTrue(trace.isRootSpanWritten());
    verify(bufferSpy).enqueue(trace);
    verify(tracer).write(argThat(it -> it.size() == 1));
  }

  @Test
  void flushClearsTheBuffer() throws InterruptedException {
    buffer.start();
    AtomicInteger counter = new AtomicInteger(0);
    // Create a fake element that newer gets written
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

    bufferSpy.enqueue(element);
    bufferSpy.enqueue(element);
    bufferSpy.enqueue(element);

    assertEquals(0, counter.get());

    buffer.flush();

    assertEquals(3, counter.get());
  }

  @Test
  void samePendingTraceIsNotEnqueuedMultipleTimes() {
    when(tracer.getPartialFlushMinSpans()).thenReturn(10000);

    PendingTrace pendingTrace = factory.create(DDTraceId.ONE);
    DDSpan span = newSpanOf(pendingTrace);
    span.finish();

    assertTrue(pendingTrace.isRootSpanWritten());
    assertEquals(0, pendingTrace.getIsEnqueued());
    assertEquals(0, delayingBuffer.getQueue().size());
    verify(tracer).write(argThat(it -> it.size() == 1));

    clearInvocations(bufferSpy, tracer, traceConfig);

    int capacity = delayingBuffer.getQueue().capacity();
    // fail to fill the buffer
    for (int i = 0; i < capacity; i++) {
      addContinuation(newSpanOf(span)).finish();
    }

    assertEquals(1, pendingTrace.getIsEnqueued());
    assertEquals(1, delayingBuffer.getQueue().size());
    verify(bufferSpy, times(capacity)).enqueue(any());

    // process the buffer
    buffer.start();

    long deadline = System.currentTimeMillis() + 3000;
    while (pendingTrace.getIsEnqueued() != 0 && System.currentTimeMillis() < deadline) {
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    assertEquals(0, pendingTrace.getIsEnqueued());
  }

  @Test
  void testingTracerFlareDumpWithMultipleTraces() throws IOException, InterruptedException {
    TracerFlare.addReporter(zip -> {}); // exercises default methods
    TracerFlare.Reporter dumpReporter = mock(TracerFlare.Reporter.class);
    TracerFlare.addReporter(dumpReporter);

    PendingTrace trace1 = factory.create(DDTraceId.ONE);
    DDSpan parent1 = newSpanOf(trace1, UNSET, System.currentTimeMillis() * 1000);
    DDSpan child1 = newSpanOf(parent1);
    PendingTrace trace2 = factory.create(DDTraceId.from(2));
    DDSpan parent2 = newSpanOf(trace2, UNSET, System.currentTimeMillis() * 2000);
    DDSpan child2 = newSpanOf(parent2);
    // first flare dump with two traces
    parent1.finish();
    parent2.finish();
    buffer.start();
    Map<String, Object> entries1 = buildAndExtractZip();

    verify(dumpReporter).prepareForFlare();
    verify(dumpReporter).addReportToFlare(any());
    verify(dumpReporter).cleanupAfterFlare();

    assertEquals(1, entries1.size());
    String pendingTraceText1 = (String) entries1.get("pending_traces.txt");
    assertTrue(
        pendingTraceText1.startsWith(
            "[{\"service\":\"fakeService\",\"name\":\"fakeOperation\",\"resource\":\"fakeResource\",\"trace_id\":1,\"span_id\":1,\"parent_id\":0"));

    List<Map<String, Object>> parsedTraces1 = parseTraceLines(pendingTraceText1);
    assertEquals(2, parsedTraces1.size());
    assertEquals(1L, ((Number) parsedTraces1.get(0).get("trace_id")).longValue());
    assertEquals(2L, ((Number) parsedTraces1.get(1).get("trace_id")).longValue());
    assertTrue(
        ((Number) parsedTraces1.get(0).get("start")).longValue()
            < ((Number) parsedTraces1.get(1).get("start")).longValue());

    clearInvocations(dumpReporter);

    // New pending traces are needed here because generating the first flare takes long enough that
    // the
    // earlier pending traces are flushed (within 500ms).
    // second flare dump with new pending traces
    // Finish the first set of traces
    child1.finish();
    child2.finish();
    // Create new pending traces
    PendingTrace trace3 = factory.create(DDTraceId.from(3));
    DDSpan parent3 = newSpanOf(trace3, UNSET, System.currentTimeMillis() * 3000);
    DDSpan child3 = newSpanOf(parent3);
    PendingTrace trace4 = factory.create(DDTraceId.from(4));
    DDSpan parent4 = newSpanOf(trace4, UNSET, System.currentTimeMillis() * 4000);
    DDSpan child4 = newSpanOf(parent4);
    parent3.finish();
    parent4.finish();
    Map<String, Object> entries2 = buildAndExtractZip();

    verify(dumpReporter).prepareForFlare();
    verify(dumpReporter).addReportToFlare(any());
    verify(dumpReporter).cleanupAfterFlare();

    assertEquals(1, entries2.size());
    String pendingTraceText2 = (String) entries2.get("pending_traces.txt");
    List<Map<String, Object>> parsedTraces2 = parseTraceLines(pendingTraceText2);
    assertEquals(2, parsedTraces2.size());

    child3.finish();
    child4.finish();

    long deadline = System.currentTimeMillis() + 3000;
    while ((trace1.size() != 0 || trace2.size() != 0 || trace3.size() != 0 || trace4.size() != 0)
        && System.currentTimeMillis() < deadline) {
      Thread.sleep(50);
    }

    assertEquals(0, trace1.size());
    assertEquals(0, trace1.getPendingReferenceCount());
    assertEquals(0, trace2.size());
    assertEquals(0, trace2.getPendingReferenceCount());
    assertEquals(0, trace3.size());
    assertEquals(0, trace3.getPendingReferenceCount());
    assertEquals(0, trace4.size());
    assertEquals(0, trace4.getPendingReferenceCount());
  }

  private DDSpan addContinuation(DDSpan span) {
    ContextScope scope = scopeManager.activateSpan(span);
    continuations.add(scopeManager.captureSpan(span));
    scope.close();
    return span;
  }

  private static DDSpan newSpanOf(PendingTrace trace) {
    return newSpanOf(trace, UNSET, 0);
  }

  private static DDSpan newSpanOf(PendingTrace trace, int samplingPriority, long timestampMicro) {
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

  private static DDSpan newSpanOf(DDSpan parent) {
    TraceCollector traceCollector = parent.spanContext().getTraceCollector();
    DDSpanContext context =
        new DDSpanContext(
            parent.spanContext().getTraceId(),
            2,
            parent.spanContext().getSpanId(),
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

  private Map<String, Object> buildAndExtractZip() throws IOException {
    TracerFlare.prepareForFlare();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(out)) {
      TracerFlare.addReportsToFlare(zip);
    } finally {
      TracerFlare.cleanupAfterFlare();
    }

    Map<String, Object> entries = new LinkedHashMap<>();
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(out.toByteArray()))) {
      ZipEntry entry;
      byte[] buf = new byte[4096];
      while ((entry = zip.getNextEntry()) != null) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int read;
        while ((read = zip.read(buf)) != -1) {
          bytes.write(buf, 0, read);
        }
        String name = entry.getName();
        if (name.endsWith(".bin")) {
          entries.put(name, bytes.toByteArray());
        } else {
          entries.put(name, new String(bytes.toByteArray(), UTF_8));
        }
      }
    }
    return entries;
  }

  private static List<Map<String, Object>> parseTraceLines(String text) throws IOException {
    List<Map<String, Object>> allSpans = new ArrayList<>();
    for (String line : text.split("\n")) {
      if (!line.isEmpty()) {
        List<Map<String, Object>> lineSpans =
            OBJECT_MAPPER.readValue(line, new TypeReference<List<Map<String, Object>>>() {});
        allSpans.addAll(lineSpans);
      }
    }
    return allSpans;
  }
}
