package datadog.trace.api.openfeature;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class StandaloneProviderRuntimeTest {

  private static final String SOURCE = "dd.feature.flags.configuration.source";
  private static final String BASE_URL = "dd.feature.flags.configuration.source.agentless.base.url";
  private static final String POLL_INTERVAL =
      "dd.feature.flags.configuration.source.agentless.poll.interval.seconds";

  @AfterEach
  void clearProperties() {
    System.clearProperty(SOURCE);
    System.clearProperty(BASE_URL);
    System.clearProperty(POLL_INTERVAL);
  }

  @Test
  void closedHandleIsSafeAndIdempotent() throws Exception {
    final HttpServer server = server();
    final AtomicInteger callbacks = new AtomicInteger();
    StandaloneProviderRuntime.Handle handle = null;
    try {
      configure(server, "30");
      handle =
          StandaloneProviderRuntime.acquire(
              StandaloneRuntimeConfiguration.resolve(), ignored -> callbacks.incrementAndGet());

      assertTrue(handle.awaitConfiguration(1, SECONDS));
      assertNotNull(handle.configuration());
      assertTrue(callbacks.get() > 0);

      handle.close();
      assertNull(handle.configuration());
      assertFalse(handle.awaitConfiguration(1, MILLISECONDS));
      handle.close();
    } finally {
      if (handle != null) {
        handle.close();
      }
      server.stop(0);
    }
  }

  @Test
  void failedStartReleasesSharedRuntime() throws Exception {
    final HttpServer server = server();
    StandaloneProviderRuntime.Handle recovered = null;
    try {
      configure(server, String.valueOf(Long.MAX_VALUE));

      assertThrows(
          ArithmeticException.class,
          () ->
              StandaloneProviderRuntime.acquire(
                  StandaloneRuntimeConfiguration.resolve(), ignored -> {}));

      System.setProperty(POLL_INTERVAL, "30");
      recovered =
          StandaloneProviderRuntime.acquire(
              StandaloneRuntimeConfiguration.resolve(), ignored -> {});
      assertTrue(recovered.awaitConfiguration(1, SECONDS));
      assertNotNull(recovered.configuration());
    } finally {
      if (recovered != null) {
        recovered.close();
      }
      server.stop(0);
    }
  }

  private static void configure(final HttpServer server, final String pollInterval) {
    System.setProperty(SOURCE, "agentless");
    System.setProperty(BASE_URL, "http://127.0.0.1:" + server.getAddress().getPort() + "/config");
    System.setProperty(POLL_INTERVAL, pollInterval);
  }

  private static HttpServer server() throws Exception {
    final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/config",
        exchange -> {
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
