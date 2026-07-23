package datadog.trace.core;

import static datadog.trace.api.DDTags.APM_ENABLED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.common.writer.ListWriter;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * When APM tracing is disabled ({@code DD_APM_TRACING_ENABLED=false}), the intake bills APM host
 * usage for every exported trace <em>chunk</em> that does not carry the {@code _dd.apm.enabled:0}
 * marker.
 * To make sure no chunk gets flushed without the correct billing tag, it gets added to every
 * single span. These tests lock that in.
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

    // Both chunks must carry the billing marker. The delayed child chunk is load-bearing: without
    // the marker the intake would bill APM host usage for this otherwise-unmarked chunk.
    assertAllSpansMarked(rootChunk, "root chunk");
    assertAllSpansMarked(childChunk, "delayed child chunk");
  }

  @Test
  void singleChunkTraceCarriesApmDisabledMarker() throws InterruptedException, TimeoutException {
    // Positive control: when the whole trace is exported as a single chunk (child finishes before
    // the root, so nothing is written until the root closes), the marker is present on every span.
    DDSpan root = (DDSpan) tracer.buildSpan("test", "root").start();
    DDSpan child = (DDSpan) tracer.buildSpan("test", "child").asChildOf(root.spanContext()).start();

    child.finish();
    assertTrue(writer.isEmpty(), "trace must not be written while the root is still open");
    root.finish();
    writer.waitForTraces(1);

    assertEquals(1, writer.size(), "expected the whole trace in a single chunk");
    List<DDSpan> chunk = writer.get(0);
    assertEquals(2, chunk.size(), "expected both spans in the same chunk");
    assertAllSpansMarked(chunk, "single-chunk trace");
  }

  @Test
  void rootlessMultiSpanChunkMarksEverySpan() throws InterruptedException, TimeoutException {
    // A multi-span chunk exported WITHOUT its local root (a partial flush / orphaned subtree): the
    // root is written first, then its descendants flush together in a later, root-less chunk. Every
    // span in that chunk must still carry the marker.
    DDSpan root = (DDSpan) tracer.buildSpan("test", "root").start();
    DDSpan childA =
        (DDSpan) tracer.buildSpan("test", "childA").asChildOf(root.spanContext()).start();
    DDSpan grandchild =
        (DDSpan) tracer.buildSpan("test", "grandchild").asChildOf(childA.spanContext()).start();

    PendingTrace trace = (PendingTrace) root.spanContext().getTraceCollector();

    // Flush the root on its own first (rootSpanWritten=true), so the descendants can only export in
    // a later, root-less chunk.
    root.finish();
    trace.write();
    writer.waitForTraces(1);
    assertEquals(1, writer.get(0).size(), "expected the root to flush alone in the first chunk");

    // Both descendants finish, then flush together in a single root-less chunk.
    childA.finish();
    grandchild.finish();
    trace.write();
    writer.waitForTraces(2);

    List<DDSpan> chunk = writer.get(1);
    assertEquals(2, chunk.size(), "expected childA and grandchild in one root-less chunk");
    assertTrue(chunk.contains(childA) && chunk.contains(grandchild), "chunk must hold both spans");
    assertTrue(!chunk.contains(root), "the local root must not be part of this chunk");

    // Every span in the root-less chunk carries the marker, including the inner grandchild whose
    // parent is present in the chunk.
    assertAllSpansMarked(chunk, "root-less descendant chunk");
  }

  @Test
  @WithConfig(key = "apm.tracing.enabled", value = "true")
  void apmTracingEnabledLeavesChunkUnmarked() throws InterruptedException, TimeoutException {
    // Negative control: the method-level override flips APM tracing back on (overriding the
    // class-level false before setup() builds the tracer), so the billing marker must NOT be
    // stamped on any span. Guards against a regression that marks unconditionally, ignoring the
    // apm.tracing.enabled flag.
    DDSpan root = (DDSpan) tracer.buildSpan("test", "root").start();
    DDSpan child = (DDSpan) tracer.buildSpan("test", "child").asChildOf(root.spanContext()).start();

    child.finish();
    root.finish();
    writer.waitForTraces(1);

    List<DDSpan> chunk = writer.get(0);
    assertEquals(2, chunk.size(), "expected both spans in the same chunk");
    for (DDSpan span : chunk) {
      assertNull(
          span.getTag(APM_ENABLED),
          "span '"
              + span.getOperationName()
              + "' must not carry _dd.apm.enabled when APM tracing is enabled");
    }
  }

  /** Every span of an exported chunk must carry the numeric {@code _dd.apm.enabled:0} marker. */
  private static void assertAllSpansMarked(List<DDSpan> chunk, String description) {
    for (DDSpan span : chunk) {
      assertEquals(
          Integer.valueOf(0),
          span.getTag(APM_ENABLED),
          description + " span '" + span.getOperationName() + "' is missing _dd.apm.enabled:0");
    }
  }
}
