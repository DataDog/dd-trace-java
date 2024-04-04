package datadog.smoketest

class SpringBootGrpcAsyncConcurrentTest extends SpringBootWithGRPCTest {
  private static final Set<String> EXPECTED_TRACES =
  [
    "[serialize]",
    "[deserialize]",
    "[grpc.server[serialize][grpc.message]]",
    "[servlet.request[spring.handler[grpc.client[grpc.message[deserialize]][serialize]][grpc.client[grpc.message[deserialize]][serialize]][grpc.client[grpc.message[deserialize]][serialize]][grpc.client[grpc.message[deserialize]][serialize]][grpc.client[grpc.message[deserialize]][serialize]][grpc.client[grpc.message[deserialize]][serialize]][grpc.client[grpc.message[deserialize]][serialize]][grpc.client[grpc.message[deserialize]][serialize]][grpc.client[grpc.message[deserialize]][serialize]][grpc.client[grpc.message[deserialize]][serialize]][grpc.client[grpc.message[deserialize]][serialize]][grpc.client[grpc.message[deserialize]][serialize]][grpc.client[grpc.message[deserialize]][serialize]][grpc.client[grpc.message[deserialize]][serialize]][grpc.client[grpc.message[deserialize]][serialize]][grpc.client[grpc.message[deserialize]][serialize]][grpc.client[grpc.message[deserialize]][serialize]][grpc.client[grpc.message[deserialize]][serialize]][grpc.client[grpc.message[deserialize]][serialize]][grpc.client[grpc.message[deserialize]][serialize]]]]",
    "[servlet.request[spring.handler[grpc.client[grpc.message[deserialize]][serialize]][grpc.client[grpc.message[deserialize]][serialize]][grpc.client[grpc.message[deserialize]][serialize]][grpc.client[grpc.message[deserialize]][serialize]][grpc.client[grpc.message[deserialize]][serialize]][grpc.client[grpc.message[deserialize]][serialize]][grpc.client[grpc.message[deserialize]][serialize]][grpc.client[grpc.message[deserialize]][serialize]][grpc.client[grpc.message[deserialize]][serialize]][grpc.client[grpc.message[deserialize]][serialize]][grpc.client[grpc.message[deserialize]][serialize]][grpc.client[grpc.message[deserialize]][serialize]][grpc.client[grpc.message[deserialize]][serialize]][grpc.client[grpc.message[deserialize]][serialize]][grpc.client[grpc.message[deserialize]][serialize]][grpc.client[grpc.message[deserialize]]][grpc.client[grpc.message[deserialize]]][grpc.client[grpc.message[deserialize]]][grpc.client[grpc.message[deserialize]]][grpc.client[grpc.message[deserialize]]]]]",
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
