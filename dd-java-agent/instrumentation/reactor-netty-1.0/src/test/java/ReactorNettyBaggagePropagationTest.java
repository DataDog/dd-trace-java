import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopSpan;
import static datadog.trace.instrumentation.netty41.AttributeKeys.CLIENT_PARENT_ATTRIBUTE_KEY;
import static datadog.trace.instrumentation.netty41.AttributeKeys.CONTEXT_ATTRIBUTE_KEY;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.Baggage;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.resources.LoopResources;

/**
 * Regression test for the W3C baggage header not propagating on outgoing Reactor Netty requests.
 *
 * <p>The bug: the connect-span path carried only {@code activeSpan()} across the subscription ->
 * I/O thread hand-off, dropping the rest of the Datadog {@code Context} (including baggage). The
 * outgoing request then had no baggage to inject, so the {@code baggage} header was silently
 * skipped. This test sets a baggage item in the active context, makes one outgoing request, and
 * asserts the server received the {@code baggage} header.
 *
 * <p>It is a <em>coupling</em> test: producer (sets baggage) -> Reactor Netty carrier -> consumer
 * (injects the header). The failure it guards against lives in the hand-off between integrations,
 * not in any one of them, so per-integration tests would not catch it.
 */
class ReactorNettyBaggagePropagationTest extends AbstractInstrumentationTest {

  private static HttpServer mockServer;
  private static ExecutorService serverExecutor;
  private static String baseUrl;
  private static final AtomicReference<String> capturedBaggage = new AtomicReference<>();

  @BeforeAll
  static void startServer() throws IOException {
    capturedBaggage.set(null);
    mockServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    mockServer.createContext(
        "/capture",
        exchange -> {
          capturedBaggage.set(exchange.getRequestHeaders().getFirst("baggage"));
          byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    mockServer.createContext(
        "/fail-body",
        exchange -> {
          try {
            byte[] buffer = new byte[256];
            while (exchange.getRequestBody().read(buffer) != -1) {
              // Wait for the request body to fail before closing the exchange.
            }
          } finally {
            exchange.close();
          }
        });
    serverExecutor = Executors.newCachedThreadPool();
    mockServer.setExecutor(serverExecutor);
    mockServer.start();
    baseUrl =
        "http://"
            + mockServer.getAddress().getHostString()
            + ":"
            + mockServer.getAddress().getPort();
  }

  @AfterAll
  static void stopServer() {
    if (mockServer != null) {
      mockServer.stop(0);
      mockServer = null;
    }
    if (serverExecutor != null) {
      serverExecutor.shutdown();
      serverExecutor = null;
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void idlePooledChannelDoesNotReactivateRequestContext(boolean withParent) throws Exception {
    ConnectionProvider connections = ConnectionProvider.create("idle-context-test", 1);
    LoopResources eventLoops = LoopResources.create("idle-context-test");
    AtomicReference<Channel> channel = new AtomicReference<>();
    AtomicReference<AgentSpan> eventSpan = new AtomicReference<>();
    AtomicReference<Baggage> eventBaggage = new AtomicReference<>();
    Object idleEvent = new Object();
    HttpClient client =
        HttpClient.create(connections)
            .runOn(eventLoops)
            .doOnConnected(
                connection -> {
                  channel.set(connection.channel());
                  connection
                      .channel()
                      .pipeline()
                      .addLast(
                          new ChannelInboundHandlerAdapter() {
                            @Override
                            public void userEventTriggered(ChannelHandlerContext ctx, Object event)
                                throws Exception {
                              if (event == idleEvent) {
                                eventSpan.set(AgentTracer.activeSpan());
                                eventBaggage.set(Baggage.fromContext(Context.current()));
                              }
                              super.userEventTriggered(ctx, event);
                            }
                          });
                });
    Channel firstChannel = null;
    try {
      for (int request = 0; request < 2; request++) {
        CountDownLatch released = new CountDownLatch(1);
        AtomicReference<AgentSpan> responseSpan = new AtomicReference<>();
        AgentSpan parent = withParent ? AgentTracer.startSpan("test", "parent-" + request) : null;
        Baggage baggage = Baggage.create(Collections.singletonMap("user.id", "user-" + request));
        Context requestContext = Context.root().with(baggage);
        if (parent != null) {
          requestContext = requestContext.with(parent);
        }
        try (ContextScope scope = requestContext.attach()) {
          client
              .doOnResponse((response, connection) -> responseSpan.set(AgentTracer.activeSpan()))
              .doOnDisconnected(connection -> released.countDown())
              .get()
              .uri(baseUrl + "/capture")
              .responseContent()
              .aggregate()
              .asString()
              .block(Duration.ofSeconds(10));
        } finally {
          if (parent != null) {
            parent.finish();
          }
        }
        assertSame(parent == null ? noopSpan() : parent, responseSpan.get());
        assertNotNull(capturedBaggage.get());
        assertTrue(capturedBaggage.get().contains("user.id=user-" + request));
        assertTrue(released.await(10, TimeUnit.SECONDS));
        Channel currentChannel = channel.get();
        if (firstChannel == null) {
          firstChannel = currentChannel;
        } else {
          assertSame(
              firstChannel, currentChannel, "the second request must reuse the pooled channel");
        }
        currentChannel
            .eventLoop()
            .submit(
                () -> {
                  assertNull(AgentTracer.activeSpan());
                  assertNull(currentChannel.attr(CONTEXT_ATTRIBUTE_KEY).get());
                  assertNull(currentChannel.attr(CLIENT_PARENT_ATTRIBUTE_KEY).get());
                  currentChannel.pipeline().fireUserEventTriggered(idleEvent);
                })
            .get(10, TimeUnit.SECONDS);
        assertNull(eventSpan.get(), "an idle channel event must not activate the previous request");
        assertNull(eventBaggage.get(), "an idle channel event must not reactivate request baggage");
      }
    } finally {
      try {
        connections.disposeLater().block(Duration.ofSeconds(10));
      } finally {
        eventLoops.disposeLater(Duration.ZERO, Duration.ofSeconds(5)).block(Duration.ofSeconds(10));
      }
    }
  }

  @Test
  void failedRequestBodyDoesNotRetainParentAfterChannelClose() throws Exception {
    ConnectionProvider connections = ConnectionProvider.create("failed-body-test", 1);
    LoopResources eventLoops = LoopResources.create("failed-body-test");
    AtomicReference<Channel> channel = new AtomicReference<>();
    AtomicReference<AgentSpan> disconnectSpan = new AtomicReference<>();
    CountDownLatch inactive = new CountDownLatch(1);
    HttpClient client =
        HttpClient.create(connections)
            .runOn(eventLoops)
            .doOnConnected(
                connection -> {
                  channel.set(connection.channel());
                  connection
                      .channel()
                      .pipeline()
                      .addFirst(
                          new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelInactive(ChannelHandlerContext ctx)
                                throws Exception {
                              try {
                                super.channelInactive(ctx);
                              } finally {
                                inactive.countDown();
                              }
                            }
                          });
                })
            .doOnDisconnected(
                connection ->
                    disconnectSpan.compareAndSet(
                        null,
                        AgentSpan.fromContext(
                            connection.channel().attr(CONTEXT_ATTRIBUTE_KEY).get())));
    AgentSpan parent = AgentTracer.startSpan("test", "failed-body-parent");
    IllegalStateException failure = new IllegalStateException("request body failed");
    try {
      try (ContextScope scope = AgentTracer.activateSpan(parent)) {
        assertSame(
            failure,
            assertThrows(
                IllegalStateException.class,
                () ->
                    client
                        .post()
                        .uri(baseUrl + "/fail-body")
                        .send(
                            (request, outbound) ->
                                outbound.sendString(
                                    Flux.concat(Mono.just("payload"), Mono.error(failure))))
                        .responseContent()
                        .aggregate()
                        .asString()
                        .block(Duration.ofSeconds(10))));
      } finally {
        parent.finish();
      }
      assertTrue(inactive.await(10, TimeUnit.SECONDS));
      assertNotNull(disconnectSpan.get(), "disconnect must occur while the client span is stored");
      assertNotSame(
          parent, disconnectSpan.get(), "exercise disconnect before Netty restores the parent");
      channel
          .get()
          .eventLoop()
          .submit(
              () -> {
                assertNull(channel.get().attr(CONTEXT_ATTRIBUTE_KEY).get());
                assertNull(channel.get().attr(CLIENT_PARENT_ATTRIBUTE_KEY).get());
              })
          .get(10, TimeUnit.SECONDS);
      writer.waitForTraces(1);
      long parentId = parent.getSpanId();
      assertTrue(
          writer.get(0).stream()
              .anyMatch(span -> span.getParentId() == parentId && span.isFinished()),
          "Netty must still finish the client span");
    } finally {
      try {
        connections.disposeLater().block(Duration.ofSeconds(10));
      } finally {
        eventLoops.disposeLater(Duration.ZERO, Duration.ofSeconds(5)).block(Duration.ofSeconds(10));
      }
    }
  }

  @Test
  void baggageHeaderPropagatedOnOutgoingRequest() {
    Baggage baggage = Baggage.create(Collections.singletonMap("user.id", "abc123"));

    AgentSpan span = AgentTracer.startSpan("test", "parent");
    try (ContextScope spanScope = AgentTracer.activateSpan(span)) {
      // Active context now carries both the span and the baggage — the exact shape the connect-span
      // path must carry across the subscription -> I/O thread hand-off.
      try (ContextScope baggageScope = Context.current().with(baggage).attach()) {
        HttpClient.create()
            .get()
            .uri(baseUrl + "/capture")
            .responseContent()
            .aggregate()
            .asString()
            .block(Duration.ofSeconds(10));
      }
    } finally {
      span.finish();
    }

    String header = capturedBaggage.get();
    assertNotNull(
        header,
        "outgoing request must carry a W3C 'baggage' header when baggage is in the active context;"
            + " null means the connect-span path dropped the context (carried only the span)");
    assertTrue(
        header.contains("user.id=abc123"),
        "baggage header should contain the propagated item, was: " + header);
  }
}
