package test.published.dependencies;

import datadog.opentracing.DDTracer;
import datadog.trace.api.Trace;
import io.opentracing.util.GlobalTracer;

public class App {
  public static void main(String[] args) {
    DDTracer tracer = DDTracer.builder().serviceName("TestService").build();
    GlobalTracer.registerIfAbsent(tracer);
    datadog.trace.api.GlobalTracer.registerIfAbsent(tracer);
    new App().tracedMethod();
  }

  @Trace
  void tracedMethod() {}
}
