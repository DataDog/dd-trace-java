package datadog.smoketest

class SpringBootGrpcAsyncConcurrentTest extends SpringBootWithGRPCTest {
  @Override
  Set<String> expectedTraces() {
    return ["[grpc.server[grpc.message]]",
            "[servlet.request[spring.handler[grpc.client[grpc.message]][grpc.client[grpc.message]][grpc.client[grpc.message]][grpc.client[grpc.message]][grpc.client[grpc.message]][grpc.client[grpc.message]][grpc.client[grpc.message]][grpc.client[grpc.message]][grpc.client[grpc.message]][grpc.client[grpc.message]][grpc.client[grpc.message]][grpc.client[grpc.message]][grpc.client[grpc.message]][grpc.client[grpc.message]][grpc.client[grpc.message]][grpc.client[grpc.message]][grpc.client[grpc.message]][grpc.client[grpc.message]][grpc.client[grpc.message]][grpc.client[grpc.message]]]]"].toSet()
  }

  @Override
  String route() {
    return "async_concurrent_greeting"
  }
}
