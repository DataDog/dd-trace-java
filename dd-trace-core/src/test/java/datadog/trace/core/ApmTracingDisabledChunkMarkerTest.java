package datadog.trace.core;

import static datadog.trace.api.DDTags.APM_ENABLED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.common.writer.ListWriter;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * When APM tracing is disabled ({@code DD_APM_TRACING_ENABLED=false}), the intake bills APM host
 * usage for every exported trace <em>chunk</em> that does not carry the {@code _dd.apm.enabled:0}
 * marker. The marker used to be stamped only on the local root span (via {@code
 * Config.getLocalRootSpanTags()}), on the assumption that the local root is present in every
 * exported chunk.
 *
 * <p>That assumption breaks when a child span outlives its local root: the root flushes in one
 * chunk (with the marker) and the long-lived child flushes later, alone, in a separate chunk with
 * no marker — so the intake charges APM host billing for an ASM-only service. The fix stamps the
 * marker on the root-most span of each exported chunk in {@link CoreTracer#write}; this test locks
 * that in by reproducing the split-chunk case.
 */
@WithConfig(key = "apm.tracing.enabled", value = "false")
class ApmTracingDisabledChunkMarkerTest extends DDCoreJavaSpecification {

  private ListWriter writer;
  private CoreTracer tracer;

  @Override
  protected boolean useStrictTraceWrites() {
    // Production standalone-ASM uses the delaying PendingTraceBuffer, which is what lets a buffered
    // root be written on its own before a late child arrives. Strict writes would instead select
    // the discarding buffer and coalesce both spans into a single chunk, hiding the bug.
    return false;
  }

  @BeforeEach
  void setup() {
    writer = new ListWriter();
    tracer = tracerBuilder().writer(writer).build();
  }

  @AfterEach
  void cleanup() {
    if (tracer != null) {
      tracer.close();
    }
  }

  @Test
  void everyExportedChunkCarriesApmDisabledMarker() throws InterruptedException, TimeoutException {
    DDSpan root = (DDSpan) tracer.buildSpan("test", "root").start();
    DDSpan child = (DDSpan) tracer.buildSpan("test", "child").asChildOf(root.spanContext()).start();

    PendingTrace trace = (PendingTrace) root.spanContext().getTraceCollector();

    // Root finishes while the child is still running -> root is buffered, not yet written.
    root.finish();
    assertTrue(writer.isEmpty());

    // Write the buffered root out on its own chunk. This sets rootSpanWritten=true and is exactly
    // what the PendingTraceBuffer worker does when a buffered root ages out (SEND_DELAY_NS).
    trace.write();
    writer.waitForTraces(1);

    // The long-lived child finishes later. Because the root was already written, it flushes alone.
    child.finish();
    trace.write();
    writer.waitForTraces(2);

    List<DDSpan> rootChunk = writer.get(0);
    List<DDSpan> childChunk = writer.get(1);
    assertEquals(1, rootChunk.size(), "expected the root to flush alone in the first chunk");
    assertEquals(1, childChunk.size(), "expected the child to flush alone in the second chunk");
    assertEquals(root, rootChunk.get(0), "first chunk should be the local root");
    assertEquals(child, childChunk.get(0), "second chunk should be the delayed child");

    // Sanity: the child really is a child of the local root (a local, non-remote parent) — this is
    // exactly the case that stamping the marker only on the local root span would miss.
    assertEquals(root.getSpanId(), child.getParentId());

    // The root chunk carries the marker, on its root-most (service-entry) span.
    assertTrue(chunkHasApmDisabledMarker(rootChunk), "root chunk should carry _dd.apm.enabled:0");
    assertTrue(
        rootSpanHasApmDisabledMarker(rootChunk),
        "root chunk marker must sit on the service-entry span");

    // Load-bearing: the delayed child chunk MUST also carry the billing marker, on the orphaned
    // child that tops that root-less chunk. Without it the intake would bill APM for this chunk.
    assertTrue(
        chunkHasApmDisabledMarker(childChunk),
        "delayed child chunk is missing _dd.apm.enabled:0 -> intake will bill APM host usage");
    assertTrue(
        rootSpanHasApmDisabledMarker(childChunk),
        "orphaned child chunk marker must sit on its root-most span");
  }

  @Test
  void singleChunkTraceCarriesApmDisabledMarker() throws InterruptedException, TimeoutException {
    // Positive control: when the whole trace is exported as a single chunk (child finishes before
    // the root, so nothing is written until the root closes), the marker is present. This isolates
    // chunk-splitting (not a broken config or a missing marker) as the origin of the bug, and
    // guards against a regression in the normal path.
    DDSpan root = (DDSpan) tracer.buildSpan("test", "root").start();
    DDSpan child = (DDSpan) tracer.buildSpan("test", "child").asChildOf(root.spanContext()).start();

    child.finish();
    assertTrue(writer.isEmpty(), "trace must not be written while the root is still open");
    root.finish();
    writer.waitForTraces(1);

    assertEquals(1, writer.size(), "expected the whole trace in a single chunk");
    List<DDSpan> chunk = writer.get(0);
    assertEquals(2, chunk.size(), "expected both spans in the same chunk");
    assertTrue(chunkHasApmDisabledMarker(chunk), "single-chunk trace must carry _dd.apm.enabled:0");
    assertTrue(
        rootSpanHasApmDisabledMarker(chunk),
        "single-chunk marker must sit on the service-entry span, not an inner child");
  }

  @Test
  void multiSpanChunkMarksRootEvenWhenNotFirstSpan() throws InterruptedException, TimeoutException {
    // Reproduces the async-framework failure: the local root and a later-finishing child are
    // exported together in a single chunk, but the root is not first in finish order. Marking
    // merely chunk.get(0) would stamp the child and leave the service-entry span (which the intake
    // and the ASM-standalone system tests inspect) unmarked -> KeyError on _dd.apm.enabled.
    DDSpan root = (DDSpan) tracer.buildSpan("test", "root").start();
    DDSpan child = (DDSpan) tracer.buildSpan("test", "child").asChildOf(root.spanContext()).start();

    // Root finishes first with pending work -> buffered, not yet written. Crucially there is no
    // intervening trace.write() here (unlike everyExportedChunkCarriesApmDisabledMarker), so
    // rootSpanWritten stays false and the root is not flushed on its own.
    root.finish();
    assertTrue(writer.isEmpty(), "buffered root must not be written while the child is still open");

    // The child finishes while the root is still buffered, so both flush together in one chunk.
    child.finish();
    writer.waitForTraces(1);

    assertEquals(1, writer.size(), "expected the root and child in a single chunk");
    List<DDSpan> chunk = writer.get(0);
    assertEquals(2, chunk.size(), "expected both spans in the same chunk");

    // The child finished last, so it -- not the service-entry root -- is first in the chunk. This
    // is exactly the ordering that made marking chunk.get(0) stamp the wrong span.
    assertNotEquals(
        root, chunk.get(0), "child finished last, so it should precede the root in the chunk");

    assertTrue(chunkHasApmDisabledMarker(chunk), "chunk must carry _dd.apm.enabled:0");
    assertTrue(
        rootSpanHasApmDisabledMarker(chunk),
        "marker must sit on the service-entry root span, not the first-finished child");
  }

  /**
   * The intake keeps a chunk out of APM billing when any of its spans carries _dd.apm.enabled:0.
   */
  private static boolean chunkHasApmDisabledMarker(List<DDSpan> chunk) {
    return chunk.stream().anyMatch(span -> Integer.valueOf(0).equals(span.getTag(APM_ENABLED)));
  }

  /**
   * The marker must sit on the root-most span of the chunk (the local root / service-entry span, or
   * an orphaned child that tops a root-less chunk), not merely on whichever span happens to be
   * first in finish order. This guards against the marker landing on an inner child while the
   * service-entry span the intake inspects goes unmarked.
   */
  private static boolean rootSpanHasApmDisabledMarker(List<DDSpan> chunk) {
    Set<Long> ids = chunk.stream().map(DDSpan::getSpanId).collect(Collectors.toSet());
    return chunk.stream()
        .filter(span -> !ids.contains(span.getParentId()))
        .allMatch(span -> Integer.valueOf(0).equals(span.getTag(APM_ENABLED)));
  }
}
