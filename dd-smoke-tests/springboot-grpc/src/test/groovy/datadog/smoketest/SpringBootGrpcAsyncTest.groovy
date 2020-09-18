package datadog.smoketest

import java.util.concurrent.atomic.AtomicInteger

class SpringBootGrpcAsyncTest extends SpringBootWithGRPCTest {
  private static final Set<String> EXPECTED_TRACES =
    ["[grpc.server[grpc.message]]",
     "[servlet.request[spring.handler[grpc.client[grpc.message]]]]"].toSet()

  @Override
  boolean isAcceptable(Map<String, AtomicInteger> traceCounts) {
    assertTraceCounts(EXPECTED_TRACES, traceCounts)
  }

  @Override
  String route() {
    return "async_greeting"
  }
}
