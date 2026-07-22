package datadog.trace.core;

import static datadog.trace.api.DDTags.APM_ENABLED;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * marker. Today the marker is only stamped on the local root span (via {@code
 * Config.getLocalRootSpanTags()}), on the assumption that the local root is present in every
 * exported chunk.
 *
 * <p>That assumption breaks when a child span outlives its local root: the root flushes in one
 * chunk (with the marker) and the long-lived child flushes later, alone, in a separate chunk with
 * no marker — so the intake charges APM host billing for an ASM-only service.
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
    // exactly the case the current start-time logic skips.
    assertEquals(root.getSpanId(), child.getParentId());

    // The root chunk carries the marker.
    assertTrue(chunkHasApmDisabledMarker(rootChunk), "root chunk should carry _dd.apm.enabled:0");

    // Load-bearing: the delayed child chunk MUST also carry the billing marker. This assertion
    // fails today, reproducing the bug — the intake would bill APM for this unmarked chunk.
    assertTrue(
        chunkHasApmDisabledMarker(childChunk),
        "delayed child chunk is missing _dd.apm.enabled:0 -> intake will bill APM host usage");
  }

  @Test
  void singleChunkTraceCarriesApmDisabledMarker() throws InterruptedException, TimeoutException {
    // Positive control: when the whole trace is exported as a single chunk (child finishes before
    // the root, so nothing is written until the root closes), the marker is present. This isolates
    // chunk-splitting (not a broken config or a missing marker) as the origin of the bug, and
    // guards
    // against a regression in the normal path.
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
  }

  /**
   * The intake keeps a chunk out of APM billing when any of its spans carries _dd.apm.enabled:0.
   */
  private static boolean chunkHasApmDisabledMarker(List<DDSpan> chunk) {
    return chunk.stream().anyMatch(span -> Integer.valueOf(0).equals(span.getTag(APM_ENABLED)));
  }
}
