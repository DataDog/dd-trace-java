package datadog.smoketest

class SpringBootGrpcSpannerTest extends SpringBootWithGRPCTest {
  @Override
  Set<String> expectedTraces() {
    return [
      "[servlet.request[spring.handler[SpannerTask.spannerResultSet[grpc.client][grpc.client][grpc.client][grpc.client][http.request][http.request]]]]",
      "[servlet.request[spring.handler[SpannerTask.spannerResultSet[grpc.client][grpc.client][grpc.client][grpc.client]]]]"].toSet()
  }

  @Override
  String route() {
    return "spanner"
  }
}
