package datadog.smoketest.opentracing;

import datadog.trace.api.DDTags;
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.util.GlobalTracer;

public class OTWithAgentApplication {

  public static void main(final String[] args) throws InterruptedException {
    final Tracer tracer = GlobalTracer.get();

    boolean throwError = args != null && args.length > 0 && Boolean.parseBoolean(args[0]);
    ApiVerification.verifyInterceptors(datadog.trace.api.GlobalTracer.get(), throwError);

    final Span span = tracer.buildSpan("someOperation").start();
    try (final Scope ignored = tracer.activateSpan(span)) {
      span.setTag(DDTags.SERVICE_NAME, "someService");
    }

    span.finish();

    // Allow trace to be reported.
    Thread.sleep(1000);
  }
}
