package datadog.trace.api.naming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import datadog.trace.api.naming.v0.ClientNamingV0;
import datadog.trace.api.naming.v0.ServerNamingV0;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ClientNamingV0OperationNameTest {

  private final ClientNamingV0 clientNaming = new ClientNamingV0();
  private final ServerNamingV0 serverNaming = new ServerNamingV0();

  @ParameterizedTest
  @MethodSource("wellKnownComponents")
  void wellKnownHttpClientComponentsHaveDistinctOperationNames(
      String component, String expectedOperation) {
    assertEquals(expectedOperation, clientNaming.operationForComponent(component));
  }

  static Stream<Arguments> wellKnownComponents() {
    return Stream.of(
        Arguments.of("apache-httpclient", "apache-httpclient.request"),
        Arguments.of("apache-httpclient5", "apache-httpclient.request"),
        Arguments.of("apache-httpasyncclient", "apache-httpasyncclient.request"),
        Arguments.of("commons-http-client", "commons-http-client.request"),
        Arguments.of("google-http-client", "google-http-client.request"),
        Arguments.of("http-url-connection", "http-url-connection.request"),
        Arguments.of("java-http-client", "java-http-client.request"),
        Arguments.of("grizzly-http-async-client", "grizzly-http-async-client.request"),
        Arguments.of("spring-webflux-client", "spring-webflux-client.request"),
        Arguments.of("synapse-client", "synapse-client.request"),
        Arguments.of("jetty-client", "jetty-client.request"));
  }

  @ParameterizedTest
  @MethodSource("preExistingComponents")
  void preExistingExplicitEntriesAreNotAffected(String component, String expectedOperation) {
    assertEquals(expectedOperation, clientNaming.operationForComponent(component));
  }

  static Stream<Arguments> preExistingComponents() {
    return Stream.of(
        Arguments.of("okhttp", "okhttp.request"),
        Arguments.of("play-ws", "play-ws.request"),
        Arguments.of("netty-client", "netty.client.request"),
        Arguments.of("akka-http-client", "akka-http.client.request"),
        Arguments.of("pekko-http-client", "pekko-http.client.request"),
        Arguments.of("jax-rs.client", "jax-rs.client.call"));
  }

  @Test
  void unknownComponentsStillFallBackToHttpRequest() {
    assertEquals("http.request", clientNaming.operationForComponent("some-custom-http-client"));
    assertEquals("http.request", clientNaming.operationForComponent("unknown"));
  }

  @ParameterizedTest
  @MethodSource("fixedComponents")
  void apmEndpointsViewClientAndServerSpansProduceDistinctAggregationKeys(String component) {
    String method = "GET";
    String path = "/api/users";
    String serverOpName = serverNaming.operationForComponent("java-web-servlet");
    String clientOpName = clientNaming.operationForComponent(component);
    assertNotEquals(
        serverOpName + method + path,
        clientOpName + method + path,
        "client and server spans for " + component + " must produce distinct Endpoints view keys");
  }

  @ParameterizedTest
  @MethodSource("fixedComponents")
  void fixedClientOperationNamesDoNotCollideWithServletRequestServerOperation(String component) {
    assertNotEquals(
        serverNaming.operationForComponent("java-web-servlet"),
        clientNaming.operationForComponent(component));
  }

  @ParameterizedTest
  @MethodSource("fixedComponents")
  void fixedClientOperationNamesDoNotCollideWithServerOperationForProtocolHttp(String component) {
    assertNotEquals(
        serverNaming.operationForProtocol("http"), clientNaming.operationForComponent(component));
  }

  static Stream<String> fixedComponents() {
    return Stream.of(
        "apache-httpclient",
        "apache-httpclient5",
        "apache-httpasyncclient",
        "commons-http-client",
        "google-http-client",
        "http-url-connection",
        "java-http-client",
        "grizzly-http-async-client",
        "spring-webflux-client",
        "synapse-client",
        "jetty-client");
  }
}
