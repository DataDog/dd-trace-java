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
    boolean initialMappingObserved = false;
    boolean updatedMappingObserved = false;

    while (System.nanoTime() - startTime < TIMEOUT_IN_NANOS) {
      Span span = tracer.buildSpan("someOperation").start();
      span.setTag(DDTags.SERVICE_NAME, ORIGINAL_SERVICE_NAME);
      String serviceName = ((MutableSpan) span).getServiceName();

      if (!initialMappingObserved && serviceName.equals(MAPPED_SERVICE_NAME)) {
        System.out.println("Initial service mapping updated to dynamic value");
        initialMappingObserved = true;
      }

      // Wait for a second mapping (simulate a new config pushed after startup)
      if (initialMappingObserved && !updatedMappingObserved && serviceName.equals("baz")) {
        System.out.println("Updated service mapping applied");
        updatedMappingObserved = true;
        break;
      }

      Thread.sleep(500);
    }

    if (initialMappingObserved && updatedMappingObserved) {
      System.exit(0);
    } else if (initialMappingObserved) {
      System.out.println("Only initial mapping observed");
      System.exit(2);
    } else {
      System.out.println("Service mapping never updated");
      System.exit(1);
    }
  }
}
