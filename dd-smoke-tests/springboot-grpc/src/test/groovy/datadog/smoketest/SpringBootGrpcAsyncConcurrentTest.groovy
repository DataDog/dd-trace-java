package datadog.smoketest

class SpringBootGrpcAsyncConcurrentTest extends SpringBootWithGRPCTest {
  private static final Set<String> EXPECTED_TRACES =
  [
    "[grpc.server[grpc.message]]",
    "[servlet.request[spring.handler[grpc.client[grpc.message]][grpc.client[grpc.message]][grpc.client[grpc.message]][grpc.client[grpc.message]][grpc.client[grpc.message]][grpc.client[grpc.message]][grpc.client[grpc.message]][grpc.client[grpc.message]][grpc.client[grpc.message]][grpc.client[grpc.message]][grpc.client[grpc.message]][grpc.client[grpc.message]][grpc.client[grpc.message]][grpc.client[grpc.message]][grpc.client[grpc.message]][grpc.client[grpc.message]][grpc.client[grpc.message]][grpc.client[grpc.message]][grpc.client[grpc.message]][grpc.client[grpc.message]]]]"
  ]
  .toSet()

  @Override
  protected Set<String> expectedTraces() {
    return EXPECTED_TRACES
  }

  @Override
  String route() {
    return "async_concurrent_greeting"
  }
}
