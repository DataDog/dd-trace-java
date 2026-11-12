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
 * A fast stand-in for a healthy Datadog Agent, for benchmarking only.
 *
 * <p>It serves a real Agent's {@code /info} response ({@code agent-info.json}, captured from Agent
 * 7.80.3), so the tracer's feature discovery matches a real Agent: client-side stats, dropping
 * unsampled traces, peer tags. Remote Config is not advertised, so the tracer doesn't poll it. Trace
 * and stats payloads are read, discarded and answered immediately.
 *
 * <p>Every response carries {@code Datadog-Agent-State}, the SHA-256 of {@code /info}, as a real
 * Agent's do. The tracer compares it with the hash it took at discovery, and a missing header looks
 * like an Agent change, so it would rerun discovery after every payload.
 *
 * <p>A local Agent can be slow or rejecting payloads without anyone noticing, and the tracer's writer
 * then backs up and holds traces in the heap, which changes the GC behavior being measured. The stub
 * takes that variable out, and {@code GET /_stats} reports what it received, one line per endpoint
 * ({@code <path> <requests> <bytes>}), so a runner can check every variant actually sent traces.
 *
 * <pre>java AgentStub.java [port] [agent-info.json]   (defaults: 8126, ./agent-info.json)</pre>
 *
 * <p>Environment: {@code STUB_SAMPLE_RATE} (default 1) is the rate returned in {@code
 * rate_by_service}; {@code STUB_DELAY_MS} delays every trace and stats response, and {@code
 * STUB_ERROR_CODE} answers them with that status, to benchmark a slow or rejecting Agent.
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
    // OTLP/http (/v1/traces etc.) answers with protobuf; an empty body is an empty Export*Response.
    boolean otlp = exchange.getRequestURI().getPath().startsWith("/v1/");
    exchange.getResponseHeaders().set("Content-Type", otlp ? "application/x-protobuf" : "application/json");
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
