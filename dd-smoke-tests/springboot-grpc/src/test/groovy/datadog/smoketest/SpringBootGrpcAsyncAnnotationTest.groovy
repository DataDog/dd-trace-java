package datadog.smoketest

class SpringBootGrpcAsyncAnnotationTest extends SpringBootWithGRPCTest {
  @Override
  Set<String> expectedTraces() {
    return ["[grpc.server[grpc.message]]",
            "[servlet.request[spring.handler[AsyncTask.greet[grpc.client[grpc.message]]]]]"].toSet()
  }

  @Override
  String route() {
    return "async_annotation_greeting"
  }
}
