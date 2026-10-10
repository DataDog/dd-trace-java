import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.regex.Pattern.compile;
import static java.util.regex.Pattern.quote;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.core.DDSpan;
import java.net.URI;
import java.util.List;
import java.util.concurrent.ExecutionException;
import org.eclipse.jetty.client.Response;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.core.FrameHandler;
import org.eclipse.jetty.websocket.core.client.CoreClientUpgradeRequest;
import org.eclipse.jetty.websocket.core.client.WebSocketCoreClient;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;
import org.junit.jupiter.api.Test;

class JettyWebSocketUpgradeTest extends AbstractInstrumentationTest {
  @Test
  void httpClientSpanFinishesWhenWebSocketUpgradeSucceeds() throws Exception {
    Server server = new Server(0);
    WebSocketClient client = new WebSocketClient();
    try {
      URI uri = startServer(server);
      client.start();

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

  @Test
  void httpClientSpanRecordsFailureSwallowedByWebSocketUpgrade() throws Exception {
    Server server = new Server(0);
    WebSocketCoreClient client = new WebSocketCoreClient();
    try {
      URI uri = startServer(server);
      client.start();
      IllegalStateException upgradeFailure = new IllegalStateException("Endpoint upgrade failed");
      FrameHandler frameHandler = mock(FrameHandler.class);
      CoreClientUpgradeRequest request =
          new CoreClientUpgradeRequest(client, uri) {
            @Override
            public FrameHandler getFrameHandler() {
              return frameHandler;
            }

            @Override
            public void upgrade(Response response, EndPoint endPoint) {
              EndPoint failingEndPoint = mock(EndPoint.class, delegatesTo(endPoint));
              doThrow(upgradeFailure).when(failingEndPoint).upgrade(any(Connection.class));
              super.upgrade(response, failingEndPoint);
            }
          };

      ExecutionException failure =
          assertThrows(ExecutionException.class, () -> client.connect(request).get(5, SECONDS));
      assertSame(upgradeFailure, failure.getCause());

      assertTraces(
          trace(
              span()
                  .operationName(compile(quote("http.request")))
                  .resourceName(compile(quote("GET /upgrade")))
                  .type(DDSpanTypes.HTTP_CLIENT)
                  .root()
                  .error(true)),
          trace(span().type(DDSpanTypes.HTTP_SERVER).error(false)));
      DDSpan handshake =
          writer.stream()
              .flatMap(List::stream)
              .filter(s -> "client".equals(s.getTag("span.kind")))
              .findFirst()
              .orElseThrow(() -> new AssertionError("Missing client handshake span"));
      assertEquals(101, handshake.getTag("http.status_code"));
      assertEquals(IllegalStateException.class.getName(), handshake.getTag("error.type"));
      assertEquals(upgradeFailure.getMessage(), handshake.getTag("error.message"));
    } finally {
      try {
        client.stop();
      } finally {
        server.stop();
      }
    }
  }

  private static URI startServer(Server server) throws Exception {
    ContextHandler context = new ContextHandler("/");
    server.setHandler(context);
    context.setHandler(
        WebSocketUpgradeHandler.from(server, context)
            .configure(
                container ->
                    container.addMapping(
                        "/upgrade", (request, response, callback) -> new Endpoint())));
    server.start();
    return URI.create(
        "ws://localhost:"
            + ((ServerConnector) server.getConnectors()[0]).getLocalPort()
            + "/upgrade");
  }

  public static class Endpoint implements Session.Listener.AutoDemanding {}
}
