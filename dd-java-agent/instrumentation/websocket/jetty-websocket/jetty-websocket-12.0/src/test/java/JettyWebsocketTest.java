import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TagsMatcher.defaultTags;
import static datadog.trace.agent.test.assertions.TagsMatcher.includes;
import static datadog.trace.agent.test.assertions.TagsMatcher.tag;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace;
import static datadog.trace.api.DDTags.DECISION_MAKER_INHERITED;
import static datadog.trace.api.DDTags.DECISION_MAKER_RESOURCE;
import static datadog.trace.api.DDTags.DECISION_MAKER_SERVICE;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.WEBSOCKET_CLOSE_CODE;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.WEBSOCKET_CLOSE_REASON;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.WEBSOCKET_MESSAGE_FRAMES;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.WEBSOCKET_MESSAGE_LENGTH;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.WEBSOCKET_MESSAGE_RECEIVE_TIME;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.WEBSOCKET_MESSAGE_TYPE;
import static datadog.trace.bootstrap.instrumentation.api.Tags.HTTP_URL;
import static datadog.trace.test.junit.utils.assertions.Matchers.is;
import static datadog.trace.test.junit.utils.assertions.Matchers.isNull;
import static datadog.trace.test.junit.utils.assertions.Matchers.matches;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.regex.Pattern.compile;
import static java.util.regex.Pattern.quote;
import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.assertions.SpanLinkMatcher;
import datadog.trace.agent.test.assertions.SpanMatcher;
import datadog.trace.agent.test.assertions.TagsMatcher;
import datadog.trace.api.DDSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanLink;
import datadog.trace.bootstrap.instrumentation.api.SpanAttributes;
import datadog.trace.core.DDSpan;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.api.UpgradeRequest;
import org.eclipse.jetty.websocket.api.WebSocketContainer;
import org.eclipse.jetty.websocket.common.JettyWebSocketFrameHandler;
import org.eclipse.jetty.websocket.core.Behavior;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.server.internal.ServerFrameHandlerFactory;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;
import org.tabletest.junit.TypeConverter;

public class JettyWebsocketTest extends AbstractInstrumentationTest {
  private static final String URL = "ws://inmemory/test/param";

  @TableTest({
    "scenario            | endpoint    | msgType",
    "full text           | full        | text   ",
    "full binary         | full        | binary ",
    "partial text        | partial     | text   ",
    "partial binary      | partial     | binary ",
    "POJO full text      | pojoFull    | text   ",
    "POJO full binary    | pojoFull    | binary ",
    "POJO partial text   | pojoPartial | text   ",
    "POJO partial binary | pojoPartial | binary "
  })
  void nativeJettyAdvices(JettyEndpoints.EndpointEvents endpoint, String msgType) throws Exception {
    JettyWebSocketFrameHandler frameHandler = createFrameHandler(endpoint);
    Callback.Completable messageCallback = new Callback.Completable();
    Callback.Completable closeCallback = new Callback.Completable();

    openFrameHandler(frameHandler);
    frameHandler.onFrame(new Frame(opcode(msgType), "hello world"), messageCallback);
    messageCallback.get(5, SECONDS);
    frameHandler.onFrame(CloseStatus.toFrame(CloseStatus.NORMAL, "bye"), closeCallback);
    closeCallback.get(5, SECONDS);

    assertNotNull(endpoint.session);
    assertEquals(singletonList("hello world"), endpoint.messages);
    assertEquals(1, endpoint.messageSpans.size());
    assertEquals("websocket.receive", endpoint.messageSpans.get(0).getOperationName().toString());
    assertNull(activeSpan());
    assertEquals(1000, endpoint.closeCode);
    assertEquals("bye", endpoint.closeReason);
    DDSpan handshake = handshake();
    assertTraces(
        trace(handshakeSpan()),
        trace(receiveSpan(handshake, msgType, 11, 1)),
        trace(closeSpan(handshake)));
  }

  @TableTest({
    "scenario            | endpoint    | msgType",
    "partial text        | partial     | text   ",
    "partial binary      | partial     | binary ",
    "POJO partial text   | pojoPartial | text   ",
    "POJO partial binary | pojoPartial | binary "
  })
  void fragmentedMessagesShareSpan(JettyEndpoints.EndpointEvents endpoint, String msgType)
      throws Exception {
    JettyWebSocketFrameHandler frameHandler = createFrameHandler(endpoint);
    openFrameHandler(frameHandler);
    byte opcode = opcode(msgType);

    deliver(frameHandler, new Frame(opcode, "hello ").setFin(false));

    assertNull(activeSpan());
    assertEquals(singletonList("hello "), endpoint.messages);
    assertEquals(1, writer.size());

    deliver(frameHandler, new Frame(OpCode.CONTINUATION, "world"));
    deliver(frameHandler, new Frame(opcode, "again"));

    assertEquals(asList("hello ", "world", "again"), endpoint.messages);
    assertEquals(asList(false, true, true), endpoint.finalFragments);
    assertSame(endpoint.messageSpans.get(0), endpoint.messageSpans.get(1));
    assertNotSame(endpoint.messageSpans.get(1), endpoint.messageSpans.get(2));
    assertNull(activeSpan());
    DDSpan handshake = handshake();
    assertTraces(
        trace(handshakeSpan()),
        trace(receiveSpan(handshake, msgType, 11, 2)),
        trace(receiveSpan(handshake, msgType, 5, 1)));
  }

  @TableTest({
    "scenario     | endpoint | msgType | last  | operation        ",
    "full text    | full     | text    | true  | websocket.receive",
    "full binary  | full     | binary  | true  | websocket.receive",
    "partial text | partial  | text    | false | websocket.receive",
    "POJO close   | pojoFull |         | true  | websocket.close  "
  })
  void handlerFailureMarksSpanAndClosesScope(
      JettyEndpoints.EndpointEvents endpoint, String msgType, boolean last, String operation)
      throws Exception {
    endpoint.failClose = msgType == null;
    endpoint.failMessages = msgType != null;
    Frame frame =
        endpoint.failClose
            ? CloseStatus.toFrame(CloseStatus.NORMAL, "bye")
            : new Frame(opcode(msgType), "hello").setFin(last);
    JettyWebSocketFrameHandler frameHandler = createFrameHandler(endpoint);
    openFrameHandler(frameHandler);

    ExecutionException error =
        assertThrows(ExecutionException.class, () -> deliver(frameHandler, frame));

    assertNotNull(error.getCause());
    assertNull(activeSpan());
    assertTraces(
        trace(handshakeSpan()),
        trace(
            span()
                .operationName(compile(quote(operation)))
                .resourceName(compile(quote("websocket /test/param")))
                .type(DDSpanTypes.WEBSOCKET)
                .error()));
    DDSpan failedSpan = writer.get(1).get(0);
    assertEquals(IllegalStateException.class.getName(), failedSpan.getTag("error.type"));
    assertEquals("handler failed", failedSpan.getTag("error.message"));
    assertInstanceOf(String.class, failedSpan.getTag("error.stack"));
  }

  @TableTest({
    "scenario        | behavior | traced",
    "untraced server | SERVER   | false ",
    "traced client   | CLIENT   | true  "
  })
  void doesNotTraceNativeMessages(Behavior behavior, boolean traced) throws Exception {
    JettyEndpoints.FullListener endpoint = new JettyEndpoints.FullListener();
    JettyWebSocketFrameHandler frameHandler = createFrameHandler(endpoint);

    openFrameHandler(frameHandler, behavior, traced);
    deliver(frameHandler, new Frame(OpCode.TEXT, "hello"));
    deliver(frameHandler, CloseStatus.toFrame(CloseStatus.NORMAL, "bye"));

    assertEquals(singletonList("hello"), endpoint.messages);
    assertEquals(singletonList(null), endpoint.messageSpans);
    assertEquals(1000, endpoint.closeCode);
    assertNull(activeSpan());
    if (traced) {
      assertTraces(trace(handshakeSpan()));
    } else {
      assertTraces();
    }
  }

  @TableTest({
    "scenario              | endpoint    | last ",
    "full                  | full        | true ",
    "partial final         | partial     | true ",
    "partial nonfinal      | partial     | false",
    "POJO full             | pojoFull    | true ",
    "POJO partial nonfinal | pojoPartial | false"
  })
  void binaryCallbackFailureMarksReceiveSpan(JettyEndpoints.EndpointEvents endpoint, boolean last)
      throws Exception {
    endpoint.failCallback = true;
    JettyWebSocketFrameHandler frameHandler = createFrameHandler(endpoint);
    openFrameHandler(frameHandler);

    ExecutionException error =
        assertThrows(
            ExecutionException.class,
            () -> deliver(frameHandler, new Frame(OpCode.BINARY, "hello").setFin(last)));

    assertEquals("callback failed", error.getCause().getMessage());
    assertNull(activeSpan());
    writer.waitForTraces(2);
    assertTrue(endpoint.messageSpans.get(0).isError());
    assertEquals("callback failed", endpoint.messageSpans.get(0).getTag("error.message"));
  }

  @TableTest({
    "scenario | fail ",
    "success  | false",
    "failure  | true "
  })
  void deferredBinaryCallbackCompletesReceiveSpan(boolean fail) throws Exception {
    JettyEndpoints.FullListener endpoint = new JettyEndpoints.FullListener();
    endpoint.deferCallback = true;
    JettyWebSocketFrameHandler frameHandler = createFrameHandler(endpoint);
    Callback.Completable callback = new Callback.Completable();
    openFrameHandler(frameHandler);

    frameHandler.onFrame(new Frame(OpCode.BINARY, "hello"), callback);

    assertNotNull(endpoint.pendingCallback);
    assertFalse(callback.isDone());
    assertEquals(1, writer.size());
    assertNull(activeSpan());

    CompletableFuture<Void> completion =
        CompletableFuture.runAsync(
            () -> {
              if (fail) {
                endpoint.pendingCallback.fail(new IllegalStateException("callback failed"));
              } else {
                endpoint.pendingCallback.succeed();
              }
              assertNull(activeSpan());
            });
    completion.get(5, SECONDS);

    assertTrue(callback.isDone());
    assertEquals(fail, callback.isCompletedExceptionally());
    writer.waitForTraces(2);
    assertEquals(fail, endpoint.messageSpans.get(0).isError());
    assertNull(activeSpan());
  }

  @TableTest({
    "scenario             | endpoint    | reverse",
    "full forward         | full        | false  ",
    "full reverse         | full        | true   ",
    "partial forward      | partial     | false  ",
    "partial reverse      | partial     | true   ",
    "POJO full forward    | pojoFull    | false  ",
    "POJO full reverse    | pojoFull    | true   ",
    "POJO partial forward | pojoPartial | false  ",
    "POJO partial reverse | pojoPartial | true   "
  })
  void pendingBinaryMessagesHaveIndependentSpans(
      JettyEndpoints.EndpointEvents endpoint, boolean reverse) throws Exception {
    endpoint.deferCallback = true;
    JettyWebSocketFrameHandler frameHandler = createFrameHandler(endpoint);
    openFrameHandler(frameHandler);
    Callback.Completable[] callbacks = {new Callback.Completable(), new Callback.Completable()};
    frameHandler.onFrame(new Frame(OpCode.BINARY, "hello"), callbacks[0]);
    org.eclipse.jetty.websocket.api.Callback first = endpoint.pendingCallback;
    frameHandler.onFrame(new Frame(OpCode.BINARY, "again"), callbacks[1]);
    org.eclipse.jetty.websocket.api.Callback second = endpoint.pendingCallback;

    assertNotSame(endpoint.messageSpans.get(0), endpoint.messageSpans.get(1));
    for (Callback.Completable callback : callbacks) {
      assertFalse(callback.isDone());
    }
    assertEquals(1, writer.size());
    assertNull(activeSpan());

    if (reverse) {
      second.fail(new IllegalStateException("second callback failed"));
    } else {
      first.succeed();
    }

    writer.waitForTraces(2);
    assertSame(endpoint.messageSpans.get(reverse ? 1 : 0), writer.get(1).get(0));
    assertFalse(callbacks[reverse ? 0 : 1].isDone());

    if (reverse) {
      first.succeed();
    } else {
      second.fail(new IllegalStateException("second callback failed"));
    }

    writer.waitForTraces(3);
    assertSame(endpoint.messageSpans.get(reverse ? 0 : 1), writer.get(2).get(0));
    assertFalse(endpoint.messageSpans.get(0).isError());
    assertTrue(endpoint.messageSpans.get(1).isError());
    assertEquals("second callback failed", endpoint.messageSpans.get(1).getTag("error.message"));
    assertTrue(callbacks[0].isDone());
    assertFalse(callbacks[0].isCompletedExceptionally());
    assertTrue(callbacks[1].isCompletedExceptionally());
    assertNull(activeSpan());
  }

  @TableTest({
    "scenario | fail ",
    "success  | false",
    "failure  | true "
  })
  void partialBinaryMessageWaitsForEarlierFragmentCallbacks(boolean fail) throws Exception {
    JettyEndpoints.PartialListener endpoint = new JettyEndpoints.PartialListener();
    endpoint.deferCallback = true;
    JettyWebSocketFrameHandler frameHandler = createFrameHandler(endpoint);
    openFrameHandler(frameHandler);
    Callback.Completable first = new Callback.Completable();
    Callback.Completable last = new Callback.Completable();
    frameHandler.onFrame(new Frame(OpCode.BINARY, "hello ").setFin(false), first);
    org.eclipse.jetty.websocket.api.Callback firstCallback = endpoint.pendingCallback;
    frameHandler.onFrame(new Frame(OpCode.CONTINUATION, "world"), last);

    endpoint.pendingCallback.succeed();
    last.get(5, SECONDS);

    assertFalse(first.isDone());
    assertEquals(1, writer.size());
    assertSame(endpoint.messageSpans.get(0), endpoint.messageSpans.get(1));

    if (fail) {
      firstCallback.fail(new IllegalStateException("first fragment failed"));
    } else {
      firstCallback.succeed();
    }

    assertTrue(first.isDone());
    assertEquals(fail, first.isCompletedExceptionally());
    writer.waitForTraces(2);
    assertEquals(fail, endpoint.messageSpans.get(0).isError());
    if (fail) {
      assertEquals("first fragment failed", endpoint.messageSpans.get(0).getTag("error.message"));
    }
    assertEquals(11L, endpoint.messageSpans.get(0).getTag(WEBSOCKET_MESSAGE_LENGTH));
    assertEquals(2L, endpoint.messageSpans.get(0).getTag(WEBSOCKET_MESSAGE_FRAMES));
    assertNull(activeSpan());
  }

  @Test
  void handlerFailureAfterCallbackSuccessStillMarksReceiveSpan() throws Exception {
    JettyEndpoints.FullListener endpoint = new JettyEndpoints.FullListener();
    endpoint.failAfterCallback = true;
    JettyWebSocketFrameHandler frameHandler = createFrameHandler(endpoint);
    openFrameHandler(frameHandler);

    frameHandler.onFrame(new Frame(OpCode.BINARY, "hello"), new Callback.Completable());

    writer.waitForTraces(2);
    assertTrue(endpoint.messageSpans.get(0).isError());
    assertEquals("handler failed", endpoint.messageSpans.get(0).getTag("error.message"));
    assertNull(activeSpan());
  }

  @TableTest({
    "scenario                        | msgType | closeFrame | closeHandler",
    "text close frame with handler   | text    | true       | true        ",
    "binary close frame with handler | binary  | true       | true        ",
    "text closed with handler        | text    | false      | true        ",
    "binary closed with handler      | binary  | false      | true        ",
    "text close frame no handler     | text    | true       | false       ",
    "binary close frame no handler   | binary  | true       | false       ",
    "text closed no handler          | text    | false      | false       ",
    "binary closed no handler        | binary  | false      | false       "
  })
  void terminationFinishesFragmentedReceiveSpan(
      String msgType, boolean closeFrame, boolean closeHandler) throws Exception {
    JettyEndpoints.EndpointEvents endpoint =
        closeHandler ? new JettyEndpoints.PartialListener() : new JettyEndpoints.NoCloseEndpoint();
    JettyWebSocketFrameHandler frameHandler = createFrameHandler(endpoint);
    openFrameHandler(frameHandler);
    deliver(frameHandler, new Frame(opcode(msgType), "hello").setFin(false));
    assertEquals(1, writer.size());

    if (closeFrame) {
      deliver(frameHandler, CloseStatus.toFrame(CloseStatus.NORMAL, "bye"));
    } else {
      Callback.Completable callback = new Callback.Completable();
      frameHandler.onClosed(new CloseStatus(CloseStatus.NORMAL, "bye"), callback);
      callback.get(5, SECONDS);
    }

    assertNull(activeSpan());
    DDSpan handshake = handshake();
    if (closeHandler) {
      assertTraces(
          trace(handshakeSpan()),
          trace(receiveSpan(handshake, msgType, 5, 1)),
          trace(closeSpan(handshake)));
    } else {
      assertTraces(trace(handshakeSpan()), trace(receiveSpan(handshake, msgType, 5, 1)));
    }
  }

  @Test
  void deferredPartialBinarySuccessKeepsSpanOpenUntilFinalCallback() throws Exception {
    JettyEndpoints.PartialListener endpoint = new JettyEndpoints.PartialListener();
    endpoint.deferCallback = true;
    JettyWebSocketFrameHandler frameHandler = createFrameHandler(endpoint);
    openFrameHandler(frameHandler);
    Callback.Completable first = new Callback.Completable();
    Callback.Completable last = new Callback.Completable();

    frameHandler.onFrame(new Frame(OpCode.BINARY, "hello ").setFin(false), first);
    endpoint.pendingCallback.succeed();
    first.get(5, SECONDS);
    frameHandler.onFrame(new Frame(OpCode.CONTINUATION, "world"), last);

    assertFalse(last.isDone());
    assertEquals(1, writer.size());
    assertSame(endpoint.messageSpans.get(0), endpoint.messageSpans.get(1));

    endpoint.pendingCallback.succeed();
    last.get(5, SECONDS);

    assertTraces(trace(handshakeSpan()), trace(receiveSpan(handshake(), "binary", 11, 2)));
    assertNull(activeSpan());
  }

  @TableTest({
    "scenario | last ",
    "nonfinal | false",
    "final    | true "
  })
  void terminationFinishesAllPendingBinaryMessages(boolean last) throws Exception {
    JettyEndpoints.PartialListener endpoint = new JettyEndpoints.PartialListener();
    endpoint.deferCallback = true;
    JettyWebSocketFrameHandler frameHandler = createFrameHandler(endpoint);
    openFrameHandler(frameHandler);
    Callback.Completable previousMessage = new Callback.Completable();
    frameHandler.onFrame(new Frame(OpCode.BINARY, "previous"), previousMessage);
    org.eclipse.jetty.websocket.api.Callback previousCallback = endpoint.pendingCallback;
    Callback.Completable message = new Callback.Completable();
    frameHandler.onFrame(new Frame(OpCode.BINARY, "hello").setFin(last), message);

    assertEquals(1, writer.size());
    assertNotSame(endpoint.messageSpans.get(0), endpoint.messageSpans.get(1));

    Callback.Completable closed = new Callback.Completable();
    frameHandler.onClosed(new CloseStatus(CloseStatus.NORMAL, "bye"), closed);
    closed.get(5, SECONDS);

    writer.waitForTraces(4);
    assertTrue(
        writer.stream().flatMap(List::stream).collect(toList()).containsAll(endpoint.messageSpans));

    endpoint.pendingCallback.succeed();
    message.get(5, SECONDS);
    previousCallback.succeed();
    previousMessage.get(5, SECONDS);

    assertEquals(4, writer.size());
    assertNull(activeSpan());
  }

  @TypeConverter
  public static JettyEndpoints.EndpointEvents endpoint(String name) {
    switch (name) {
      case "full":
        return new JettyEndpoints.FullListener();
      case "partial":
        return new JettyEndpoints.PartialListener();
      case "pojoFull":
        return new JettyEndpoints.PojoFullEndpoint();
      case "pojoPartial":
        return new JettyEndpoints.PojoPartialEndpoint();
      default:
        throw new IllegalArgumentException("Unknown endpoint: " + name);
    }
  }

  private static byte opcode(String msgType) {
    return "text".equals(msgType) ? OpCode.TEXT : OpCode.BINARY;
  }

  private static JettyWebSocketFrameHandler createFrameHandler(Object endpoint) {
    ServerFrameHandlerFactory factory =
        new ServerFrameHandlerFactory(mock(WebSocketContainer.class), new WebSocketComponents());
    JettyWebSocketFrameHandler frameHandler = factory.newJettyFrameHandler(endpoint);
    UpgradeRequest request = mock(UpgradeRequest.class);
    when(request.getRequestURI()).thenReturn(URI.create(URL));
    frameHandler.setUpgradeRequest(request);
    return frameHandler;
  }

  private static void openFrameHandler(JettyWebSocketFrameHandler frameHandler) throws Exception {
    openFrameHandler(frameHandler, Behavior.SERVER, true);
  }

  private static void openFrameHandler(
      JettyWebSocketFrameHandler frameHandler, Behavior connectionBehavior, boolean traced)
      throws Exception {
    CoreSession session =
        new CoreSession.Empty() {
          @Override
          public Behavior getBehavior() {
            return connectionBehavior;
          }
        };
    Callback.Completable openCallback = new Callback.Completable();
    if (traced) {
      runUnderTrace(
          "parent",
          () -> {
            activeSpan().setTag(HTTP_URL, URL);
            frameHandler.onOpen(session, openCallback);
            openCallback.get(5, SECONDS);
            return null;
          });
    } else {
      frameHandler.onOpen(session, openCallback);
      openCallback.get(5, SECONDS);
    }
  }

  private static void deliver(JettyWebSocketFrameHandler frameHandler, Frame frame)
      throws Exception {
    Callback.Completable callback = new Callback.Completable();
    frameHandler.onFrame(frame, callback);
    callback.get(5, SECONDS);
  }

  private static DDSpan handshake() throws Exception {
    writer.waitForTraces(1);
    return writer.get(0).get(0);
  }

  private static SpanMatcher handshakeSpan() {
    return span()
        .root()
        .operationName(compile(quote("parent")))
        .resourceName(compile(quote("/test/param")))
        .tags(defaultTags(), tag(HTTP_URL, is(URL)));
  }

  private static SpanMatcher receiveSpan(
      DDSpan handshake, String msgType, long length, long frames) {
    return websocketSpan(
        handshake,
        "websocket.receive",
        tag(WEBSOCKET_MESSAGE_TYPE, matches(quote(msgType))),
        tag(WEBSOCKET_MESSAGE_LENGTH, is(length)),
        tag(WEBSOCKET_MESSAGE_FRAMES, is(frames)),
        // Full-message callbacks do not record a receive-time tag.
        includes(WEBSOCKET_MESSAGE_RECEIVE_TIME));
  }

  private static SpanMatcher closeSpan(DDSpan handshake) {
    return websocketSpan(
        handshake,
        "websocket.close",
        tag(WEBSOCKET_MESSAGE_TYPE, isNull()),
        tag(WEBSOCKET_MESSAGE_LENGTH, isNull()),
        tag(WEBSOCKET_MESSAGE_FRAMES, isNull()),
        tag(WEBSOCKET_CLOSE_CODE, is(1000)),
        tag(WEBSOCKET_CLOSE_REASON, is("bye")));
  }

  private static SpanMatcher websocketSpan(
      DDSpan handshake, String operation, TagsMatcher... extraTags) {
    List<TagsMatcher> tags = new ArrayList<>(asList(extraTags));
    tags.add(defaultTags());
    tags.add(tag("span.kind", is("consumer")));
    tags.add(tag("component", matches("websocket")));
    tags.add(tag("peer.hostname", isNull()));
    tags.add(tag(DECISION_MAKER_INHERITED, is(1)));
    tags.add(tag(DECISION_MAKER_SERVICE, is(handshake.getServiceName())));
    tags.add(tag(DECISION_MAKER_RESOURCE, matches(quote(handshake.getResourceName().toString()))));
    byte flags =
        handshake.getSamplingPriority() > 0
            ? AgentSpanLink.SAMPLED_FLAG
            : AgentSpanLink.DEFAULT_FLAGS;
    return span()
        .root()
        .operationName(compile(quote(operation)))
        .resourceName(compile(quote("websocket /test/param")))
        .type(DDSpanTypes.WEBSOCKET)
        .links(
            SpanLinkMatcher.to(handshake)
                .traceFlags(flags)
                .attributes(SpanAttributes.builder().put("dd.kind", "executed_from").build()))
        .tags(tags.toArray(new TagsMatcher[0]));
  }
}
