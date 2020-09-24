package datadog.smoketest

import spock.lang.Ignore

import java.util.concurrent.atomic.AtomicInteger

@Ignore("can unignore when strict continuation reference counting is dropped")
class SpringBootGrpcSpannerNoAsyncTest extends SpringBootWithGRPCTest {

  @Override
  boolean isAcceptable(Map<String, AtomicInteger> traceCounts) {
    // currently spring @Async instrumentation doesn't hold the trace back
    // if spans are created after the @Async span finishes - compensate for this
    for (Map.Entry<String, AtomicInteger> entry : traceCounts.entrySet()) {
      if (entry.getKey().startsWith("[servlet.request[spring.handler[grpc.client]") && entry.getValue().get() > 0) {
        return true
      }
    }
    return false
  }

  @Override
  String route() {
    return "spanner_no_async"
  }
}
