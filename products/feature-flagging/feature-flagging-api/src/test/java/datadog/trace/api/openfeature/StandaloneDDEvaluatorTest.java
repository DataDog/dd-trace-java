package datadog.trace.api.openfeature;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import dev.openfeature.sdk.ErrorCode;
import dev.openfeature.sdk.MutableContext;
import dev.openfeature.sdk.ProviderEvaluation;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class StandaloneDDEvaluatorTest {

  private static final String ENABLED = "dd.feature.flags.enabled";
  private static final String SOURCE = "dd.feature.flags.configuration.source";
  private static final String BASE_URL = "dd.feature.flags.configuration.source.agentless.base.url";

  @AfterEach
  void clearProperties() {
    System.clearProperty(ENABLED);
    System.clearProperty(SOURCE);
    System.clearProperty(BASE_URL);
  }

  @Test
  void ownsAndSharesCdnRuntimeLifecycle() throws Exception {
    final AtomicInteger requests = new AtomicInteger();
    final HttpServer server = server(requests);
    final StandaloneDDEvaluator first = new StandaloneDDEvaluator(() -> {});
    final AtomicInteger callbacks = new AtomicInteger();
    final StandaloneDDEvaluator second = new StandaloneDDEvaluator(callbacks::incrementAndGet);
    try {
      System.setProperty(SOURCE, "agentless");
      System.setProperty(BASE_URL, "http://127.0.0.1:" + server.getAddress().getPort() + "/config");
      final MutableContext context = new MutableContext("subject");

      assertFalse(first.hasConfiguration());
      final ProviderEvaluation<String> before =
          first.evaluate(String.class, "message", "default", context);
      assertEquals("default", before.getValue());
      assertEquals(ErrorCode.PROVIDER_NOT_READY, before.getErrorCode());

      assertTrue(first.initialize(1, SECONDS, context));
      assertTrue(first.initialize(1, SECONDS, context));
      assertEquals("hello", first.evaluate(String.class, "message", "default", context).getValue());

      assertTrue(second.initialize(1, SECONDS, context));
      assertTrue(callbacks.get() > 0);
      assertEquals(1, requests.get());

      first.shutdown();
      first.shutdown();
      assertFalse(first.hasConfiguration());
      assertEquals(
          "hello", second.evaluate(String.class, "message", "default", context).getValue());
    } finally {
      first.shutdown();
      second.shutdown();
      server.stop(0);
    }
  }

  @Test
  void rejectsDisabledRemoteConfigAndMismatchedSharedConfiguration() throws Exception {
    final MutableContext context = new MutableContext("subject");
    System.setProperty(ENABLED, "false");
    assertThrows(
        IllegalStateException.class,
        () -> new StandaloneDDEvaluator(() -> {}).initialize(1, SECONDS, context));

    System.clearProperty(ENABLED);
    System.setProperty(SOURCE, "remote_config");
    assertThrows(
        IllegalStateException.class,
        () -> new StandaloneDDEvaluator(() -> {}).initialize(1, SECONDS, context));

    final HttpServer server = server(new AtomicInteger());
    final StandaloneDDEvaluator first = new StandaloneDDEvaluator(() -> {});
    final StandaloneDDEvaluator second = new StandaloneDDEvaluator(() -> {});
    try {
      System.setProperty(SOURCE, "agentless");
      System.setProperty(BASE_URL, "http://127.0.0.1:" + server.getAddress().getPort() + "/config");
      assertTrue(first.initialize(1, SECONDS, context));

      System.setProperty(BASE_URL, "http://127.0.0.1:" + server.getAddress().getPort() + "/other");
      assertThrows(IllegalStateException.class, () -> second.initialize(1, SECONDS, context));
    } finally {
      first.shutdown();
      second.shutdown();
      server.stop(0);
    }
  }

  private static HttpServer server(final AtomicInteger requests) throws Exception {
    final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/config",
        exchange -> {
          requests.incrementAndGet();
          final byte[] response = UFC.getBytes(UTF_8);
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.start();
    return server;
  }

  private static final String UFC =
      "{\"format\":\"SERVER\",\"environment\":{\"name\":\"test\"},\"flags\":{"
          + "\"message\":{\"key\":\"message\",\"enabled\":true,\"variationType\":\"STRING\","
          + "\"variations\":{\"on\":{\"key\":\"on\",\"value\":\"hello\"}},"
          + "\"allocations\":[{\"key\":\"allocation\",\"rules\":[],\"splits\":["
          + "{\"variationKey\":\"on\",\"shards\":[]}],\"doLog\":true}]}}}";
}
