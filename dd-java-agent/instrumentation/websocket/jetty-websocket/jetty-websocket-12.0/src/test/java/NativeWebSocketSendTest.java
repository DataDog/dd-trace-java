import static datadog.trace.agent.test.assertions.SpanLinkMatcher.to;
import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TagsMatcher.defaultTags;
import static datadog.trace.agent.test.assertions.TagsMatcher.tag;
import static datadog.trace.agent.test.assertions.TraceMatcher.SORT_BY_START_TIME;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.WEBSOCKET_MESSAGE_FRAMES;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.WEBSOCKET_MESSAGE_LENGTH;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.WEBSOCKET_MESSAGE_TYPE;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_URL;
import static datadog.trace.test.junit.utils.assertions.Matchers.is;
import static datadog.trace.test.junit.utils.assertions.Matchers.matches;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.regex.Pattern.compile;
import static java.util.regex.Pattern.quote;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.assertions.SpanMatcher;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanLink;
import datadog.trace.bootstrap.instrumentation.api.SpanAttributes;
import datadog.trace.core.DDSpan;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.api.WebSocketContainer;
import org.eclipse.jetty.websocket.common.JettyWebSocketFrameHandler;
import org.eclipse.jetty.websocket.core.Behavior;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.exception.ProtocolException;
import org.eclipse.jetty.websocket.server.internal.ServerFrameHandlerFactory;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class NativeWebSocketSendTest extends AbstractInstrumentationTest {
  @TableTest({
    "scenario       | binary | partial",
    "text           | false  | false  ",
    "binary         | true   | false  ",
    "partial text   | false  | true   ",
    "partial binary | true   | true   "
  })
  void clientSendsWithoutParentAreNotTraced(boolean binary, boolean partial) throws Exception {
    Connection connection = new Connection(true, Behavior.CLIENT);
    RecordingCallback callback = new RecordingCallback();

    connection.send(binary, partial, "hello", true, callback);
    connection.callbacks.get(0).succeeded();
    callback.get(5, SECONDS);

    assertNull(connection.spans.get(0));
    assertNull(callback.span);
    assertNull(activeSpan());
    assertTraces(trace(handshakeSpan()));
  }

  @TableTest({
    "scenario       | binary | partial",
    "text           | false  | false  ",
    "binary         | true   | false  ",
    "partial text   | false  | true   ",
    "partial binary | true   | true   "
  })
  void clientSendsUseActiveParent(boolean binary, boolean partial) throws Exception {
    Connection connection = new Connection(true, Behavior.CLIENT);
    RecordingCallback callback = new RecordingCallback();

    runUnderTrace(
        "application",
        () -> {
          AgentSpan application = activeSpan();
          connection.send(binary, partial, "hello", true, callback);
          assertSame(application, activeSpan());
          return null;
        });
    connection.callbacks.get(0).succeeded();
    callback.get(5, SECONDS);

    assertNull(activeSpan());
    AgentSpan sent = connection.spans.get(0);
    assertNotNull(sent);
    assertSame(sent, callback.span);
    assertTraces(
        trace(handshakeSpan()),
        trace(
            SORT_BY_START_TIME,
            span().operationName("application").root(),
            sendSpan(connection, binary, 5, 1).childOfPrevious()));
  }

  @TableTest({
    "scenario | binary",
    "text     | false ",
    "binary   | true  "
  })
  void clientPartialSendKeepsParentUntilLastFragment(boolean binary) throws Exception {
    Connection connection = new Connection(true, Behavior.CLIENT);
    runUnderTrace(
        "application",
        () -> {
          connection.send(binary, true, "hello ", false, null);
          return null;
        });
    assertNull(activeSpan());
    connection.send(binary, true, "world", true, null);
    connection.callbacks.get(1).succeeded();
    connection.callbacks.get(0).succeeded();

    assertNotNull(connection.spans.get(0));
    assertSame(connection.spans.get(0), connection.spans.get(1));
    assertNull(activeSpan());
    assertTraces(
        trace(handshakeSpan()),
        trace(
            SORT_BY_START_TIME,
            span().operationName("application").root(),
            sendSpan(connection, binary, 11, 2).childOfPrevious()));
  }

  @TableTest({
    "scenario       | binary | partial | synchronous",
    "text async     | false  | false   | false      ",
    "binary async   | true   | false   | false      ",
    "text sync      | false  | false   | true       ",
    "binary sync    | true   | false   | true       ",
    "partial text   | false  | true    | false      ",
    "partial binary | true   | true    | false      "
  })
  void serverSendsWithoutParentFinishOnCallback(
      boolean binary, boolean partial, boolean synchronous) throws Exception {
    Connection connection = new Connection(true);
    connection.synchronous = synchronous;
    RecordingCallback callback = new RecordingCallback();
    assertNull(activeSpan());
    connection.send(binary, partial, "hello", true, callback);
    assertNull(activeSpan());
    AgentSpan sent = connection.spans.get(0);
    assertEquals("websocket.send", sent.getOperationName().toString());
    if (!synchronous) {
      assertEquals(1, writer.size());
      Thread completion = new Thread(() -> connection.callbacks.get(0).succeeded());
      completion.start();
      completion.join();
    }
    callback.get(5, SECONDS);
    assertSame(sent, callback.span);
    assertNull(activeSpan());
    assertTraces(trace(handshakeSpan()), trace(sendSpan(connection, binary, 5, 1).root()));
  }

  @TableTest({
    "scenario | binary",
    "text     | false ",
    "binary   | true  "
  })
  void fragmentedAndOverlappingMessages(boolean binary) throws Exception {
    Connection connection = new Connection(true);
    connection.send(binary, true, "hello ", false, null);
    connection.send(binary, true, "world", true, null);
    connection.send(binary, false, "again", true, null);
    assertSame(connection.spans.get(0), connection.spans.get(1));
    assertNotSame(connection.spans.get(1), connection.spans.get(2));

    connection.callbacks.get(1).succeeded();
    assertEquals(1, writer.size());
    connection.callbacks.get(0).succeeded();
    connection.callbacks.get(2).succeeded();
    assertNull(activeSpan());
    assertTraces(
        trace(handshakeSpan()),
        trace(sendSpan(connection, binary, 11, 2).root()),
        trace(sendSpan(connection, binary, 5, 1).root()));
  }

  @Test
  void sendUsesApplicationParentAndRestoresCallbackContext() throws Exception {
    Connection connection = new Connection(true);
    RecordingCallback callback = new RecordingCallback();
    runUnderTrace(
        "application",
        () -> {
          AgentSpan parent = activeSpan();
          connection.send(false, false, "hello", true, callback);
          assertSame(parent, activeSpan());
          assertEquals(parent.getSpanId(), ((DDSpan) connection.spans.get(0)).getParentId());
          return null;
        });
    runUnderTrace(
        "completion",
        () -> {
          AgentSpan parent = activeSpan();
          connection.callbacks.get(0).succeeded();
          assertSame(parent, activeSpan());
          return null;
        });
    assertSame(connection.spans.get(0), callback.span);
    assertTraces(
        trace(handshakeSpan()),
        trace(
            SORT_BY_START_TIME,
            span().operationName("application").root(),
            sendSpan(connection, false, 5, 1).childOfPrevious()),
        trace(span().operationName("completion").root()));
  }

  @TableTest({
    "scenario           | failureMode      | partial",
    "sync failure       | fail             | false  ",
    "throw              | throw            | false  ",
    "success then throw | successThenThrow | false  ",
    "fragment failure   | fail             | true   ",
    "fragment throw     | throw            | true   "
  })
  void synchronousFailuresFinishSpan(String failureMode, boolean partial) throws Exception {
    Connection connection = new Connection(true);
    connection.failureMode = failureMode;
    RecordingCallback callback = new RecordingCallback();
    if ("fail".equals(failureMode)) {
      connection.send(false, partial, "hello", !partial, callback);
      assertSame(connection.failure, callback.failure);
    } else {
      assertSame(
          connection.failure,
          assertThrows(
              IllegalStateException.class,
              () -> connection.send(false, partial, "hello", !partial, callback)));
    }
    assertNull(activeSpan());
    assertTraces(
        trace(handshakeSpan()),
        trace(
            span()
                .operationName(compile(quote("websocket.send")))
                .type(DDSpanTypes.WEBSOCKET)
                .error()));
    assertEquals("send failed", connection.spans.get(0).getTag("error.message"));
    // A late callback must not finish the span again.
    connection.callbacks.get(0).succeeded();
    assertEquals(2, writer.size());
  }

  @TableTest({
    "scenario | partial",
    "full     | false  ",
    "partial  | true   "
  })
  void asynchronousFailureClearsMessage(boolean partial) throws Exception {
    Connection connection = new Connection(true);
    RecordingCallback callback = new RecordingCallback();
    connection.send(false, partial, "hello", !partial, callback);
    connection.callbacks.get(0).failed(connection.failure);
    assertSame(connection.failure, callback.failure);
    assertSame(connection.spans.get(0), callback.span);
    connection.send(false, partial, "again", true, null);
    connection.callbacks.get(1).succeeded();
    assertNotSame(connection.spans.get(0), connection.spans.get(1));
    assertNull(activeSpan());
    assertTraces(
        trace(handshakeSpan()),
        trace(
            span()
                .operationName(compile(quote("websocket.send")))
                .type(DDSpanTypes.WEBSOCKET)
                .error()),
        trace(sendSpan(connection, false, 5, 1).root()));
  }

  @Test
  void callbackFailureClosesScopeAndFinishesSpan() throws Exception {
    Connection connection = new Connection(true);
    connection.send(
        false,
        false,
        "hello",
        true,
        new org.eclipse.jetty.websocket.api.Callback() {
          @Override
          public void succeed() {
            throw connection.failure;
          }

          @Override
          public void fail(Throwable failure) {}
        });
    assertSame(
        connection.failure,
        assertThrows(IllegalStateException.class, () -> connection.callbacks.get(0).succeeded()));
    assertNull(activeSpan());
    assertTraces(
        trace(handshakeSpan()),
        trace(
            span()
                .operationName(compile(quote("websocket.send")))
                .type(DDSpanTypes.WEBSOCKET)
                .error()));
  }

  @Test
  void closeFinishesIncompleteSendsAndWaitsForPendingCallbacks() throws Exception {
    Connection connection = new Connection(true);
    connection.send(false, true, "hello", false, null);
    connection.callbacks.get(0).succeeded();
    assertEquals(1, writer.size());
    connection.send(false, false, "again", true, null);
    Callback.Completable closed = new Callback.Completable();
    connection.handler.onClosed(new CloseStatus(CloseStatus.NORMAL, "bye"), closed);
    closed.get(5, SECONDS);
    assertTraces(trace(handshakeSpan()), trace(sendSpan(connection, false, 5, 1).root()));
    connection.callbacks.get(1).succeeded();
    assertTraces(
        trace(handshakeSpan()),
        trace(sendSpan(connection, false, 5, 1).root()),
        trace(sendSpan(connection, false, 5, 1).root()));
    connection.callbacks.forEach(Callback::succeeded);
    assertEquals(3, writer.size());
    assertNull(activeSpan());
  }

  @TableTest({
    "scenario            | binary",
    "text rejects binary | false ",
    "binary rejects text | true  "
  })
  void rejectedPartialSendPreservesUnfinishedMessage(boolean binary) throws Exception {
    Connection connection = new Connection(true);
    connection.send(binary, true, "hello", false, null);
    connection.callbacks.get(0).succeeded();
    AgentSpan unfinished = connection.spans.get(0);
    RecordingCallback rejected = new RecordingCallback();

    connection.send(!binary, true, "rejected", false, rejected);

    assertInstanceOf(ProtocolException.class, rejected.failure);
    assertNotSame(unfinished, rejected.span);
    SpanMatcher failedSend =
        span()
            .operationName(compile(quote("websocket.send")))
            .type(DDSpanTypes.WEBSOCKET)
            .root()
            .error();
    assertTraces(trace(handshakeSpan()), trace(failedSend));

    Callback.Completable closed = new Callback.Completable();
    connection.handler.onClosed(new CloseStatus(CloseStatus.NORMAL, "bye"), closed);
    closed.get(5, SECONDS);

    assertTraces(
        trace(handshakeSpan()),
        trace(sendSpan(connection, binary, 5, 1).root()),
        trace(failedSend));
    assertNull(activeSpan());
  }

  @TableTest({
    "scenario        | partial | failed",
    "full success    | false   | false ",
    "full failure    | false   | true  ",
    "partial success | true    | false ",
    "partial failure | true    | true  "
  })
  void peerClosePreservesPendingSendOutcome(boolean partial, boolean failed) throws Exception {
    Connection connection = new Connection(true);
    RecordingCallback callback = new RecordingCallback();
    connection.send(false, partial, "hello", !partial, callback);
    Callback.Completable closed = new Callback.Completable();
    connection.handler.onFrame(new CloseStatus(CloseStatus.NORMAL, "bye").toFrame(), closed);
    closed.get(5, SECONDS);
    assertEquals(1, writer.size());

    if (failed) {
      connection.callbacks.get(0).failed(connection.failure);
      assertSame(connection.failure, callback.failure);
      assertEquals("send failed", connection.spans.get(0).getTag("error.message"));
    } else {
      connection.callbacks.get(0).succeeded();
      callback.get(5, SECONDS);
    }
    assertSame(connection.spans.get(0), callback.span);
    assertNull(activeSpan());
    SpanMatcher expected =
        failed
            ? span()
                .operationName(compile(quote("websocket.send")))
                .type(DDSpanTypes.WEBSOCKET)
                .error()
            : sendSpan(connection, false, 5, 1);
    assertTraces(trace(handshakeSpan()), trace(expected.root()));
    connection.callbacks.get(0).succeeded();
    assertEquals(2, writer.size());
  }

  @Test
  void partialTextPreservesNullCallbackFailure() throws Exception {
    Connection connection = new Connection(true);
    assertThrows(
        NullPointerException.class, () -> connection.session.sendPartialText("hello", true, null));
    assertNull(activeSpan());
    assertTraces(
        trace(handshakeSpan()),
        trace(
            span()
                .operationName(compile(quote("websocket.send")))
                .type(DDSpanTypes.WEBSOCKET)
                .error()));
  }

  @Test
  void emptyBinaryMessageKeepsBinaryType() throws Exception {
    Connection connection = new Connection(true);
    connection.session.sendBinary(null, null);
    connection.callbacks.get(0).succeeded();
    assertTraces(trace(handshakeSpan()), trace(sendSpan(connection, true, 0, 1).root()));
  }

  @Test
  void untracedSessionAndControlFramesDoNotCreateSendSpans() throws Exception {
    Connection untraced = new Connection(false);
    untraced.synchronous = true;
    untraced.send(false, false, "hello", true, null);
    assertNull(untraced.spans.get(0));
    assertEquals(0, writer.size());
    Connection traced = new Connection(true);
    traced.synchronous = true;
    traced.session.sendPing(ByteBuffer.allocate(0), org.eclipse.jetty.websocket.api.Callback.NOOP);
    traced.session.sendPong(ByteBuffer.allocate(0), org.eclipse.jetty.websocket.api.Callback.NOOP);
    assertNull(traced.spans.get(0));
    assertNull(traced.spans.get(1));
    assertTraces(trace(handshakeSpan()));
  }

  private static SpanMatcher handshakeSpan() {
    return span().root().operationName("handshake");
  }

  private static SpanMatcher sendSpan(
      Connection connection, boolean binary, long size, long frames) {
    return span()
        .operationName(compile(quote("websocket.send")))
        .resourceName(compile(quote("websocket /send")))
        .type(DDSpanTypes.WEBSOCKET)
        .error(false)
        .links(
            to(connection.handshake)
                .traceFlags(
                    connection.handshake.getSamplingPriority() > 0
                        ? AgentSpanLink.SAMPLED_FLAG
                        : AgentSpanLink.DEFAULT_FLAGS)
                .attributes(SpanAttributes.builder().put("dd.kind", "resuming").build()))
        .tags(
            defaultTags(),
            tag("component", matches("websocket")),
            tag("span.kind", is("producer")),
            tag(WEBSOCKET_MESSAGE_TYPE, matches(binary ? "binary" : "text")),
            tag(WEBSOCKET_MESSAGE_LENGTH, is(size)),
            tag(WEBSOCKET_MESSAGE_FRAMES, is(frames)));
  }

  private static class RecordingCallback
      extends org.eclipse.jetty.websocket.api.Callback.Completable {
    AgentSpan span;
    Throwable failure;

    @Override
    public void succeed() {
      span = activeSpan();
      super.succeed();
    }

    @Override
    public void fail(Throwable failure) {
      span = activeSpan();
      this.failure = failure;
      super.fail(failure);
    }
  }

  private static class Connection extends CoreSession.Empty {
    final List<Callback> callbacks = new ArrayList<>();
    final List<AgentSpan> spans = new ArrayList<>();
    final IllegalStateException failure = new IllegalStateException("send failed");
    final JettyWebSocketFrameHandler handler;
    final Session session;
    final Behavior behavior;
    DDSpan handshake;
    boolean synchronous;
    String failureMode = "";

    Connection(boolean traced) throws Exception {
      this(traced, Behavior.SERVER);
    }

    Connection(boolean traced, Behavior behavior) throws Exception {
      this.behavior = behavior;
      ServerFrameHandlerFactory factory =
          new ServerFrameHandlerFactory(mock(WebSocketContainer.class), new WebSocketComponents());
      handler = factory.newJettyFrameHandler(new Session.Listener.AutoDemanding() {});
      UpgradeRequest request = mock(UpgradeRequest.class);
      when(request.getRequestURI()).thenReturn(URI.create("ws://inmemory/send"));
      handler.setUpgradeRequest(request);
      Callback.Completable opened = new Callback.Completable();
      if (traced) {
        runUnderTrace(
            "handshake",
            () -> {
              handshake = (DDSpan) activeSpan();
              handshake.setResourceName("/send");
              handshake.setTag(HTTP_URL, "ws://inmemory/send");
              handler.onOpen(this, opened);
              return null;
            });
      } else {
        handler.onOpen(this, opened);
      }
      opened.get(5, SECONDS);
      session = handler.getSession();
    }

    @Override
    public Behavior getBehavior() {
      return behavior;
    }

    @Override
    public void sendFrame(Frame frame, Callback callback, boolean batch) {
      callbacks.add(callback);
      spans.add(activeSpan());
      if ("fail".equals(failureMode)) {
        callback.failed(failure);
      } else if ("throw".equals(failureMode)) {
        throw failure;
      } else if ("successThenThrow".equals(failureMode)) {
        callback.succeeded();
        throw failure;
      } else if (synchronous) {
        callback.succeeded();
      }
    }

    void send(
        boolean binary,
        boolean partial,
        String text,
        boolean last,
        org.eclipse.jetty.websocket.api.Callback callback) {
      if (binary) {
        ByteBuffer payload = UTF_8.encode(text);
        if (partial) {
          session.sendPartialBinary(payload, last, callback);
        } else {
          session.sendBinary(payload, callback);
        }
        assertEquals(text.length(), payload.remaining());
      } else if (partial) {
        session.sendPartialText(
            text,
            last,
            callback == null ? org.eclipse.jetty.websocket.api.Callback.NOOP : callback);
      } else {
        session.sendText(text, callback);
      }
    }
  }
}
