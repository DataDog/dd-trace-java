package datadog.smoketest.dynamicconfig;

import datadog.trace.api.DDTags;
import datadog.trace.api.interceptor.MutableSpan;
import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.util.GlobalTracer;
import java.util.concurrent.TimeUnit;

public class ServiceMappingApplication {
  public static final String ORIGINAL_SERVICE_NAME = "foo";
  public static final String MAPPED_SERVICE_NAME = "bar";

  public static final long TIMEOUT_IN_NANOS = TimeUnit.SECONDS.toNanos(10);

  public static void main(String[] args) throws InterruptedException {
    Tracer tracer = GlobalTracer.get();

    long startTime = System.nanoTime();

    while (System.nanoTime() - startTime < TIMEOUT_IN_NANOS) {
      Span span = tracer.buildSpan("someOperation").start();
      span.setTag(DDTags.SERVICE_NAME, ORIGINAL_SERVICE_NAME);
      String serviceName = ((MutableSpan) span).getServiceName();

      if (serviceName.equals(MAPPED_SERVICE_NAME)) {
        System.out.println("Service mapping updated to dynamic value");
        System.exit(0);
      }

      Thread.sleep(500);
    }

    System.out.println("Service mapping never updated");
    System.exit(1);
  }
}
