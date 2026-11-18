package datadog.trace.core;

import static datadog.trace.api.SizingHintTestBridge.sizeOf;
import static datadog.trace.bootstrap.instrumentation.api.Tags.COMPONENT;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_METHOD;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_URL;
import static datadog.trace.bootstrap.instrumentation.api.Tags.PEER_PORT;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_SERVER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.IdStore;
import datadog.trace.api.SizingHint;
import datadog.trace.api.config.TracerConfig;
import datadog.trace.common.writer.Writer;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Sizing hints are tuned by every written trace, including one the writer then drops without
 * serializing -- as client-side stats does for sampled-out traces. Runs in its own JVM because the
 * dense-store flag is read once.
 */
@WithConfig(key = TracerConfig.TRACE_DENSE_TAGS_ENABLED, value = "true")
class SizingHintRecordingDenseForkedTest extends DDCoreJavaSpecification {

  /** Accepts traces and drops them unserialized, like RemoteWriter's sampled-out drop policy. */
  static final class UnserializingWriter implements Writer {
    @Override
    public void write(List<DDSpan> trace) {}

    @Override
    public void start() {}

    @Override
    public boolean flush() {
      return true;
    }

    @Override
    public void close() {}

    @Override
    public void incrementDropCounts(int spanCount) {}
  }

  @Test
  void theDenseStoreIsOn() {
    // guards the rest of this class: with the flag missed there are no hints at all
    assertEquals("DenseIdStore", IdStore.create(null).getClass().getSimpleName());
  }

  @Test
  void aFreshHintIsUntuned() {
    assertEquals(0, sizeOf(SizingHint.forOperation("fresh.op", true)));
  }

  @Test
  void aTraceTheWriterDropsUnserializedStillTunesItsHint() {
    CoreTracer tracer = tracerBuilder().writer(new UnserializingWriter()).build();
    try {
      DDSpan span = (DDSpan) tracer.buildSpan("test-instr", "unserialized.op").start();
      span.setTag(COMPONENT, "test-component");
      span.setTag(SPAN_KIND, SPAN_KIND_SERVER);
      span.setTag(HTTP_METHOD, "GET");
      span.setTag(HTTP_URL, "http://localhost/");
      span.setTag(PEER_PORT, 8080);
      span.finish();

      int size = sizeOf(SizingHint.forOperation("unserialized.op", true));
      assertTrue(size >= 5, "hint tuned to the span's known tags, was " + size);
    } finally {
      tracer.close();
    }
  }
}
