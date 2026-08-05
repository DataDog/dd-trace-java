package datadog.trace.api.openfeature;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.sun.net.httpserver.HttpServer;
import dev.openfeature.sdk.MutableContext;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Child JVM entry point for the provider-only CDN smoke test. */
public final class ProviderOnlyChildMain {

  private ProviderOnlyChildMain() {}

  public static void main(final String[] args) throws Exception {
    final boolean agentAttached =
        ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
            .anyMatch(argument -> argument.startsWith("-javaagent:"));
    final AtomicInteger requests = new AtomicInteger();
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
    try {
      final Provider provider =
          new Provider(
              new Provider.Options()
                  .cdnBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/config")
                  .pollInterval(Duration.ofMillis(50))
                  .requestTimeout(Duration.ofSeconds(1))
                  .initTimeout(1, TimeUnit.SECONDS));
      final int beforeActivation = requests.get();
      provider.initialize(new MutableContext("child"));
      final String value =
          provider
              .getStringEvaluation("message", "default", new MutableContext("child"))
              .getValue();
      final int afterActivation = requests.get();
      provider.shutdown();
      final int afterShutdown = requests.get();
      Thread.sleep(150);

      System.out.println("AGENT_ATTACHED=" + agentAttached);
      System.out.println("REQUESTS_BEFORE_ACTIVATION=" + beforeActivation);
      System.out.println("REQUESTS_AFTER_ACTIVATION=" + afterActivation);
      System.out.println("REQUESTS_AFTER_SHUTDOWN=" + requests.get());
      System.out.println("VALUE=" + value);
      if (agentAttached
          || beforeActivation != 0
          || afterActivation == 0
          || requests.get() != afterShutdown
          || !"hello".equals(value)) {
        System.exit(2);
      }
    } finally {
      server.stop(0);
    }
  }

  private static final String UFC =
      "{\"format\":\"SERVER\",\"environment\":{\"name\":\"test\"},\"flags\":{"
          + "\"message\":{\"key\":\"message\",\"enabled\":true,\"variationType\":\"STRING\","
          + "\"variations\":{\"on\":{\"key\":\"on\",\"value\":\"hello\"}},"
          + "\"allocations\":[{\"key\":\"allocation\",\"rules\":[],\"splits\":["
          + "{\"variationKey\":\"on\",\"shards\":[]}],\"doLog\":false}]}}}";
}
