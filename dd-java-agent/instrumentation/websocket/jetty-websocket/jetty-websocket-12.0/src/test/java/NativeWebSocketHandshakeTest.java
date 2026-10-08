import static datadog.trace.agent.test.assertions.SpanLinkMatcher.to;
import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceMatcher.SORT_BY_START_TIME;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.WEBSOCKET_MESSAGE_LENGTH;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.WEBSOCKET_MESSAGE_TYPE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.singletonList;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.regex.Pattern.compile;
import static java.util.regex.Pattern.quote;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.assertions.SpanMatcher;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanLink;
import datadog.trace.bootstrap.instrumentation.api.SpanAttributes;
import datadog.trace.core.DDSpan;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;
import org.tabletest.junit.TableTest;
import org.tabletest.junit.TypeConverterSources;

@TypeConverterSources(NativeEndpoints.class)
class NativeWebSocketHandshakeTest extends AbstractInstrumentationTest {
  @TableTest({
    "scenario        | endpoint  | messageType",
    "listener text   | full      | text       ",
    "listener binary | full      | binary     ",
    "boxed text      | boxedFull | text       ",
    "boxed binary    | boxedFull | binary     "
  })
  void messagesLinkToHandshakesInBothDirections(
      NativeEndpoints.EndpointEvents endpoint, String messageType) throws Exception {
    endpoint.echoMessages = true;
    Server server = new Server(0);
    WebSocketClient client = new WebSocketClient();
    try {
      ContextHandler context = new ContextHandler("/");
      server.setHandler(context);
      context.setHandler(
          WebSocketUpgradeHandler.from(server, context)
              .configure(
                  container ->
                      container.addMapping("/receive", (request, response, callback) -> endpoint)));
      server.start();
      client.start();
      URI uri =
          URI.create(
              "ws://localhost:"
                  + ((ServerConnector) server.getConnectors()[0]).getLocalPort()
                  + "/receive");
      ClientEndpoint clientEndpoint = new ClientEndpoint();
      Session session =
          runUnderTrace(
              "application.connect", () -> client.connect(clientEndpoint, uri).get(5, SECONDS));
      writer.waitForTraces(2);
      DDSpan handshake = handshake("server");
      assertEquals(101, handshake.getTag("http.status_code"));

      runUnderTrace(
          "application.send",
          () -> {
            Callback.Completable sent = new Callback.Completable();
            if ("text".equals(messageType)) {
              session.sendText("hello", sent);
            } else {
              session.sendBinary(UTF_8.encode("hello"), sent);
            }
            sent.get(5, SECONDS);
            return null;
          });
      assertEquals("hello", clientEndpoint.reply.get(5, SECONDS));
      assertNotNull(clientEndpoint.messageSpan, "The client reply handler needs a receive span");
      assertEquals("websocket.receive", clientEndpoint.messageSpan.getOperationName().toString());
      assertEquals("consumer", clientEndpoint.messageSpan.getTag("span.kind"));
      assertNull(activeSpan());

      assertTraces(
          trace(
              SORT_BY_START_TIME,
              span().operationName("application.connect").root(),
              span().type(DDSpanTypes.HTTP_CLIENT).childOfPrevious().error(false)),
          trace(span().type(DDSpanTypes.HTTP_SERVER).error(false)),
          trace(
              SORT_BY_START_TIME,
              span().operationName("application.send").root(),
              messageSpan(handshake("client"), "send").childOfPrevious()),
          trace(
              SORT_BY_START_TIME,
              messageSpan(handshake, "receive").root(),
              messageSpan(handshake, "send").childOfPrevious()),
          trace(
              SORT_BY_START_TIME,
              messageSpan(handshake("client"), "receive").root(),
              span().operationName("client.handler").childOfPrevious()));
      assertEquals(singletonList("hello"), endpoint.messages);
      assertEquals(1, endpoint.messageSpans.size());
      assertEquals(
          messageType, endpoint.messageSpans.get(0).getTag(WEBSOCKET_MESSAGE_TYPE).toString());
      assertEquals(5L, endpoint.messageSpans.get(0).getTag(WEBSOCKET_MESSAGE_LENGTH));
    } finally {
      try {
        client.stop();
      } finally {
        server.stop();
      }
    }
  }

  private static DDSpan handshake(String kind) {
    return writer.stream()
        .flatMap(List::stream)
        .filter(s -> kind.equals(s.getTag("span.kind")))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Missing " + kind + " handshake span"));
  }

  private static SpanMatcher messageSpan(DDSpan handshake, String operation) {
    return span()
        .operationName(compile(quote("websocket." + operation)))
        .resourceName(compile(quote("websocket /receive")))
        .type(DDSpanTypes.WEBSOCKET)
        .error(false)
        .links(
            to(handshake)
                .traceFlags(
                    handshake.getSamplingPriority() > 0
                        ? AgentSpanLink.SAMPLED_FLAG
                        : AgentSpanLink.DEFAULT_FLAGS)
                .attributes(
                    SpanAttributes.builder()
                        .put("dd.kind", "receive".equals(operation) ? "executed_from" : "resuming")
                        .build()));
  }

  public static class ClientEndpoint implements Session.Listener.AutoDemanding {
    final CompletableFuture<String> reply = new CompletableFuture<>();
    AgentSpan messageSpan;

    @Override
    public void onWebSocketText(String message) {
      messageSpan = activeSpan();
      runUnderTrace(
          "client.handler",
          () -> {
            reply.complete(message);
            return null;
          });
    }
  }
}
