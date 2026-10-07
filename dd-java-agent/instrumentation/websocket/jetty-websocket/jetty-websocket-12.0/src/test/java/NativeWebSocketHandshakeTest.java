import static datadog.trace.agent.test.assertions.SpanLinkMatcher.to;
import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceMatcher.SORT_BY_START_TIME;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.WEBSOCKET_MESSAGE_LENGTH;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.WEBSOCKET_MESSAGE_TYPE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.singletonList;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.regex.Pattern.compile;
import static java.util.regex.Pattern.quote;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.assertions.SpanMatcher;
import datadog.trace.api.DDSpanTypes;
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
      Session session = client.connect(clientEndpoint, uri).get(5, SECONDS);
      writer.waitForTraces(2);
      DDSpan handshake = handshake("server");
      assertEquals(101, handshake.getTag("http.status_code"));

      Callback.Completable sent = new Callback.Completable();
      if ("text".equals(messageType)) {
        session.sendText("hello", sent);
      } else {
        session.sendBinary(UTF_8.encode("hello"), sent);
      }
      sent.get(5, SECONDS);
      assertEquals("hello", clientEndpoint.reply.get(5, SECONDS));

      assertTraces(
          trace(span().type(DDSpanTypes.HTTP_CLIENT).error(false)),
          trace(span().type(DDSpanTypes.HTTP_SERVER).error(false)),
          trace(sendSpan(handshake("client")).root()),
          trace(
              SORT_BY_START_TIME,
              span()
                  .root()
                  .operationName(compile(quote("websocket.receive")))
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
                              SpanAttributes.builder().put("dd.kind", "executed_from").build())),
              sendSpan(handshake).childOfPrevious()));
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

  private static SpanMatcher sendSpan(DDSpan handshake) {
    return span()
        .operationName(compile(quote("websocket.send")))
        .resourceName(compile(quote("websocket /receive")))
        .type(DDSpanTypes.WEBSOCKET)
        .error(false)
        .links(
            to(handshake)
                .traceFlags(
                    handshake.getSamplingPriority() > 0
                        ? AgentSpanLink.SAMPLED_FLAG
                        : AgentSpanLink.DEFAULT_FLAGS)
                .attributes(SpanAttributes.builder().put("dd.kind", "resuming").build()));
  }

  public static class ClientEndpoint implements Session.Listener.AutoDemanding {
    final CompletableFuture<String> reply = new CompletableFuture<>();

    @Override
    public void onWebSocketText(String message) {
      reply.complete(message);
    }
  }
}
