package datadog.smoketest.opentracing;

import datadog.opentracing.DDTracer;
import datadog.trace.api.DDTags;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.util.GlobalTracer;

public class OTWithoutAgentApplication {
  public static void main(final String[] args) throws InterruptedException {
    final DDTracer tracer = DDTracer.builder().build();
    GlobalTracer.register(tracer);
    // register the same tracer with the Datadog API
    datadog.trace.api.GlobalTracer.registerIfAbsent(tracer);

    ApiVerification.verifyInterceptors(tracer, false);

    final Span span = tracer.buildSpan("someOperation").start();
    try (final Scope scope = tracer.activateSpan(span)) {
      span.setTag(DDTags.SERVICE_NAME, "someService");
    }

    span.finish();

    // Allow trace to be reported.
    Thread.sleep(1000);
  }
}
