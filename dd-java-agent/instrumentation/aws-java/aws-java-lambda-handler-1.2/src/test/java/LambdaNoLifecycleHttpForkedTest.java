import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.eclipse.jetty.server.ServerConnector;
import datadog.trace.agent.test.server.http.JavaTestHttpServer;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/** Runs real handler advice with a listener on the extension lifecycle port. */
@WithConfig(key = "_HANDLER", value = "Handler", env = true, addPrefix = false)
@WithConfig(key = "universal.instrumentation", value = "false")
class LambdaNoLifecycleHttpForkedTest extends LambdaNativeHandlerForkedTest {
  private final AtomicInteger requests = new AtomicInteger();
  private JavaTestHttpServer lifecycle;

  @BeforeEach
  void listenForLifecycleRequests() {
    lifecycle =
        JavaTestHttpServer.httpServer(
            server -> {
              server.customizer(
                  jetty -> {
                    ServerConnector connector = (ServerConnector) jetty.getConnectors()[0];
                    connector.setHost("127.0.0.1");
                    connector.setPort(8124);
                  });
              server.handlers(
                  handlers ->
                      handlers.all(
                          api -> {
                            requests.incrementAndGet();
                            api.getResponse().status(503).send();
                          }));
            });
  }

  @AfterEach
  void noLifecycleRequestsForSuccessfulOrThrowingHandlers() {
    try {
      if (lifecycle != null) {
        lifecycle.close();
      }
    } finally {
      assertEquals(0, requests.get(), "Native tracing must not call any lifecycle endpoint");
    }
  }
}
