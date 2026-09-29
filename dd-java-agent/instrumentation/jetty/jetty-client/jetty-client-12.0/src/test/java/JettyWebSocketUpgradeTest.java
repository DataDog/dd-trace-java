import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.regex.Pattern.compile;
import static java.util.regex.Pattern.quote;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.core.DDSpan;
import java.net.URI;
import java.util.List;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;
import org.junit.jupiter.api.Test;

class JettyWebSocketUpgradeTest extends AbstractInstrumentationTest {
  @Test
  void httpClientSpanFinishesWhenWebSocketUpgradeSucceeds() throws Exception {
    Server server = new Server(0);
    WebSocketClient client = new WebSocketClient();
    try {
      ContextHandler context = new ContextHandler("/");
      server.setHandler(context);
      context.setHandler(
          WebSocketUpgradeHandler.from(server, context)
              .configure(
                  container ->
                      container.addMapping(
                          "/upgrade", (request, response, callback) -> new Endpoint())));
      server.start();
      client.start();
      URI uri =
          URI.create(
              "ws://localhost:"
                  + ((ServerConnector) server.getConnectors()[0]).getLocalPort()
                  + "/upgrade");

      Session session = client.connect(new Endpoint(), uri).get(5, SECONDS);

      assertTrue(session.isOpen());
      // The HTTP handshake must be reported before the WebSocket connection closes.
      assertTraces(
          trace(
              span()
                  .operationName(compile(quote("http.request")))
                  .resourceName(compile(quote("GET /upgrade")))
                  .type(DDSpanTypes.HTTP_CLIENT)
                  .root()
                  .error(false)),
          trace(span().type(DDSpanTypes.HTTP_SERVER).error(false)));
      DDSpan handshake =
          writer.stream()
              .flatMap(List::stream)
              .filter(s -> "client".equals(s.getTag("span.kind")))
              .findFirst()
              .orElseThrow(() -> new AssertionError("Missing client handshake span"));
      assertEquals("jetty-client", handshake.getTag("component").toString());
      assertEquals("client", handshake.getTag("span.kind"));
      assertEquals(101, handshake.getTag("http.status_code"));
    } finally {
      try {
        client.stop();
      } finally {
        server.stop();
      }
    }
  }

  public static class Endpoint implements Session.Listener.AutoDemanding {}
}
