package datadog.smoketest

class SpringBootGrpcSyncTest extends SpringBootWithGRPCTest {
  @Override
  Set<String> expectedTraces() {
    return ["[grpc.server[grpc.message]]",
            "[servlet.request[spring.handler[grpc.client[grpc.message]]]]"].toSet()
  }

  @Override
  String route() {
    return "greeting"
  }
}
