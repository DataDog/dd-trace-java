package datadog.smoketest

class SpringBootGrpcCompletableFutureTest extends SpringBootWithGRPCTest {
  private static final Set<String> EXPECTED_TRACES =
  [
    "[grpc.server[grpc.message]]",
    "[servlet.request[spring.handler[grpc.client][grpc.client][grpc.client][grpc.client][grpc.client][grpc.client][grpc.client][grpc.client][grpc.client][grpc.client][grpc.client][grpc.client][grpc.client][grpc.client][grpc.client][grpc.client][grpc.client][grpc.client][grpc.client][grpc.client]]]"
  ].toSet()

  @Override
  protected Set<String> expectedTraces() {
    return EXPECTED_TRACES
  }

  @Override
  String route() {
    return "async_cf_greeting"
  }
}
