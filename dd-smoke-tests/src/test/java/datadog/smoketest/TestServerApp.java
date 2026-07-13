package datadog.smoketest;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;

/**
 * Minimal HTTP server launched by {@link SmokeAppTest} to exercise {@link SmokeApp}'s
 * launch/log-capture/lifecycle mechanics without needing the agent or a full sample app. Reads
 * {@code --server.port=<port>} (substituted by {@code SmokeApp} from {@code ${app.httpPort}}),
 * echoes each request to stdout so log capture can be asserted, and blocks until the process is
 * destroyed.
 */
public final class TestServerApp {
  private TestServerApp() {}

  public static void main(String[] args) throws Exception {
    int port = 0;
    for (String arg : args) {
      if (arg.startsWith("--server.port=")) {
        port = Integer.parseInt(arg.substring("--server.port=".length()));
      }
    }

    HttpServer server = HttpServer.create(new InetSocketAddress("localhost", port), 0);
    server.createContext(
        "/",
        exchange -> {
          System.out.println(
              "REQUEST " + exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
          byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
          }
        });
    server.start();
    System.out.println("TestServerApp listening on " + port);

    // Block forever; SmokeApp destroys the process at teardown.
    new CountDownLatch(1).await();
  }
}
