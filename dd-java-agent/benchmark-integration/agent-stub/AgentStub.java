import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Local Datadog Agent stub for benchmarks. It reads and discards payloads so Agent processing and
 * rejection do not affect the tracer's writer and heap measurements by default.
 *
 * <p>Serves {@code agent-info.json}, captured from Agent 7.80.3, for feature discovery, including
 * client-side stats, unsampled-trace dropping and peer tags. Remote Config is not advertised. Every
 * response includes {@code Datadog-Agent-State}, the SHA-256 of that file, so trace responses match
 * the discovered state and do not trigger repeated discovery.
 *
 * <p>{@code GET /_stats} reports received requests and bytes per endpoint as {@code <path>
 * <requests> <bytes>}. The runner uses this to check that each agent variant delivered traces.
 *
 * <pre>java AgentStub.java [port] [agent-info.json]</pre>
 *
 * <p>Defaults: port 8126 and {@code ./agent-info.json}. {@code STUB_SAMPLE_RATE} sets the {@code
 * rate_by_service} response (default 1). {@code STUB_DELAY_MS} delays trace and stats responses;
 * {@code STUB_ERROR_CODE} sets their response status to simulate a degraded Agent.
 */
public class AgentStub {
  private static final Map<String, AtomicLongArray> RECEIVED = new ConcurrentHashMap<>();
  private static String agentState;

  public static void main(String[] args) throws IOException {
    int port = args.length > 0 ? Integer.parseInt(args[0]) : 8126;
    byte[] info = Files.readAllBytes(Path.of(args.length > 1 ? args[1] : "agent-info.json"));
    agentState = sha256Hex(info);
    double sampleRate = Double.parseDouble(env("STUB_SAMPLE_RATE", "1"));
    long delayMs = Long.parseLong(env("STUB_DELAY_MS", "0"));
    int errorCode = Integer.parseInt(env("STUB_ERROR_CODE", "0"));
    byte[] tracesResponse =
        ("{\"rate_by_service\":{\"service:,env:\":" + sampleRate + "}}")
            .getBytes(StandardCharsets.UTF_8);

    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 64);
    server.setExecutor(Executors.newFixedThreadPool(8));
    server.createContext(
        "/",
        exchange -> {
          try (exchange) {
            String path = exchange.getRequestURI().getPath();
            if ("GET".equals(exchange.getRequestMethod())) {
              if (path.equals("/info")) {
                reply(exchange, 200, info);
              } else if (path.equals("/_stats")) {
                reply(exchange, 200, stats());
              } else {
                reply(exchange, 404, new byte[0]);
              }
              return;
            }
            long bytes = drain(exchange.getRequestBody());
            AtomicLongArray counts = RECEIVED.computeIfAbsent(path, p -> new AtomicLongArray(2));
            counts.incrementAndGet(0);
            counts.addAndGet(1, bytes);
            boolean payload = path.endsWith("/traces") || path.endsWith("/stats");
            if (payload && delayMs > 0) {
              Thread.sleep(delayMs);
            }
            if (payload && errorCode != 0) {
              reply(exchange, errorCode, "{\"error\":\"stub degraded mode\"}".getBytes());
            } else if (path.endsWith("/traces")) {
              reply(exchange, 200, tracesResponse);
            } else if (path.startsWith("/telemetry/") || path.startsWith("/evp_proxy/")) {
              reply(exchange, 202, new byte[0]);
            } else {
              reply(exchange, 200, new byte[0]);
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
    server.start();
    System.out.println("agent stub listening on 127.0.0.1:" + port);
  }

  private static byte[] stats() {
    StringBuilder out = new StringBuilder();
    new TreeMap<>(RECEIVED)
        .forEach((path, c) -> out.append(path).append(' ').append(c.get(0)).append(' ')
            .append(c.get(1)).append('\n'));
    return out.toString().getBytes(StandardCharsets.UTF_8);
  }

  private static long drain(InputStream body) throws IOException {
    byte[] buffer = new byte[64 * 1024];
    long total = 0;
    for (int n; (n = body.read(buffer)) > 0; ) {
      total += n;
    }
    return total;
  }

  private static void reply(HttpExchange exchange, int status, byte[] body) throws IOException {
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.getResponseHeaders().set("Datadog-Agent-State", agentState);
    exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
    if (body.length > 0) {
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(body);
      }
    }
  }

  /** Lowercase hex, matching how the tracer hashes the {@code /info} body. */
  private static String sha256Hex(byte[] bytes) {
    try {
      StringBuilder hex = new StringBuilder(64);
      for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
        hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String env(String name, String fallback) {
    String value = System.getenv(name);
    return value == null || value.isEmpty() ? fallback : value;
  }
}
