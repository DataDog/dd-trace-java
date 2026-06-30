package datadog.trace.core.propagation;

import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.datastreams.NoopPathwayContext;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDCoreJavaSpecification;
import datadog.trace.core.DDSpanContext;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;

/** This class is a base test class for the {@link HttpCodec.Injector} tests. */
abstract class AbstractHttpInjectorTest extends DDCoreJavaSpecification {
  protected CoreTracer tracer;
  protected HttpCodec.Injector injector;

  /**
   * Creates the injector under test.
   *
   * @return {@code null} by default for tests that build injectors per test case.
   */
  protected HttpCodec.Injector newInjector() {
    return null;
  }

  @BeforeEach
  void setupInjectorTest() {
    this.tracer = tracerBuilder().writer(new ListWriter()).build();
    this.injector = newInjector();
  }

  /** Builds a span context with the standard fake service/operation/resource values. */
  protected DDSpanContext mockSpanContext(
      DDTraceId traceId,
      long spanId,
      int samplingPriority,
      CharSequence origin,
      Map<String, String> baggage,
      PropagationTags propagationTags) {
    return new DDSpanContext(
        traceId,
        spanId,
        DDSpanId.ZERO,
        null,
        "fakeService",
        "fakeOperation",
        "fakeResource",
        samplingPriority,
        origin,
        baggage,
        false,
        "fakeType",
        0,
        this.tracer.createTraceCollector(DDTraceId.ONE),
        null,
        null,
        NoopPathwayContext.INSTANCE,
        false,
        propagationTags);
  }
}
