package datadog.trace.core.propagation;

import static org.junit.jupiter.api.Assertions.assertSame;

import datadog.context.Context;
import datadog.context.propagation.Propagators;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDCoreJavaSpecification;
import java.util.HashMap;
import java.util.Map;
import org.tabletest.junit.TableTest;

/**
 * A forwarded trace whose propagation tags didn't change injects the x-datadog-tags value it
 * extracted, through the default propagator, as a service forwarding a request or record does.
 */
class XDatadogTagsPassthroughInjectionTest extends DDCoreJavaSpecification {

  @TableTest({
    "scenario          | traceparent                                                   | tracestate                                                   ",
    "datadog only      |                                                               |                                                              ",
    "both formats      | '00-640cfd8d00000000000000000000075bcd15-00f067aa0ba902b7-01' | 'dd=s:1;p:00f067aa0ba902b7;t.dm:-1;t.tid:640cfd8d00000000'   ",
    "both, otel member | '00-640cfd8d00000000000000000000075bcd15-00f067aa0ba902b7-01' | 'dd=s:1;p:00f067aa0ba902b7;t.dm:-1,ot=th:0;rv:a1b2c3d4e5f607'"
  })
  void forwardsTheExtractedHeader(String traceparent, String tracestate) {
    String raw = new String("_dd.p.tid=640cfd8d00000000,_dd.p.dm=-1".toCharArray());
    Map<String, String> headers = new HashMap<>();
    headers.put("x-datadog-trace-id", "123456789");
    headers.put("x-datadog-parent-id", "67667974448284343");
    headers.put("x-datadog-sampling-priority", "1");
    headers.put("x-datadog-tags", raw);
    if (traceparent != null) {
      headers.put("traceparent", traceparent);
      headers.put("tracestate", tracestate);
    }

    CoreTracer tracer = tracerBuilder().writer(new ListWriter()).build();
    try {
      Context extracted =
          Propagators.defaultPropagator()
              .extract(Context.root(), headers, (carrier, visitor) -> carrier.forEach(visitor));
      AgentSpan span =
          tracer
              .buildSpan("passthrough", "consume")
              .asChildOf(AgentSpan.fromContext(extracted).spanContext())
              .start();
      Map<String, String> injected = new HashMap<>();
      Propagators.defaultPropagator().inject(span, injected, Map::put);
      span.finish();

      assertSame(raw, injected.get("x-datadog-tags"));
    } finally {
      tracer.close();
    }
  }
}
