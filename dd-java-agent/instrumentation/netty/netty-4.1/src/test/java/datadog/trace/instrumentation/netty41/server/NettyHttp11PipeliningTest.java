package datadog.trace.instrumentation.netty41.server;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceAssertions.SORT_BY_START_TIME;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static datadog.trace.api.gateway.Events.EVENTS;
import static io.netty.handler.codec.http.HttpHeaderNames.CONNECTION;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaderNames.TRANSFER_ENCODING;
import static io.netty.handler.codec.http.HttpHeaderValues.CHUNKED;
import static io.netty.handler.codec.http.HttpHeaderValues.KEEP_ALIVE;
import static io.netty.handler.codec.http.HttpResponseStatus.CONTINUE;
import static io.netty.handler.codec.http.HttpResponseStatus.NO_CONTENT;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Collections.emptyMap;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.trace.agent.test.assertions.TraceMatcher;
import datadog.trace.api.function.TriFunction;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.gateway.SubscriptionService;
import datadog.trace.bootstrap.ActiveSubsystems;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.util.ReferenceCountUtil;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class NettyHttp11PipeliningTest extends NettyHttpServerTestSupport {

  private static final String FIRST_PATH = "/pipelined/first";
  private static final String SECOND_PATH = "/pipelined/second";
  private static final String THIRD_PATH = "/pipelined/third";
  private static final HttpResponseStatus EARLY_HINTS = new HttpResponseStatus(103, "Early Hints");

  private final PipeliningHandler handler = new PipeliningHandler();
  private Object appSecSubscriptions;
  private boolean originalAppSecActive;

  @Override
  protected void configurePipeline(Channel ch) {
    ch.pipeline().addLast(new HttpServerCodec());
    ch.pipeline().addLast(new HttpObjectAggregator(65536));
    ch.pipeline().addLast(handler);
  }

  @AfterEach
  void resetAppSec() {
    if (appSecSubscriptions != null) {
      ((SubscriptionService) appSecSubscriptions).reset();
      appSecSubscriptions = null;
      ActiveSubsystems.APPSEC_ACTIVE = originalAppSecActive;
    }
    handler.clearBlockResponseFunctionBlocking();
  }

  @Test
  void createsServerSpanForEachPipelinedRequest() throws Exception {
    handler.expectRequests(3);
    try (Socket socket = connect()) {
      socket.getOutputStream().write(pipelinedRequests().getBytes(US_ASCII));
      socket.getOutputStream().flush();

      assertTrue(
          handler.awaitAllRequestsReceived(),
          "server did not receive all pipelined requests before responding");

      handler.writeResponses();

      assertEquals("response " + FIRST_PATH, readHttpResponseBody(socket.getInputStream()));
      assertEquals("response " + SECOND_PATH, readHttpResponseBody(socket.getInputStream()));
      assertEquals("response " + THIRD_PATH, readHttpResponseBody(socket.getInputStream()));
    }

    assertTraces(
        SORT_BY_START_TIME,
        serverTrace(FIRST_PATH),
        serverTrace(SECOND_PATH),
        serverTrace(THIRD_PATH));
  }

  @Test
  void requestBlockOnLaterPipelinedRequestDoesNotOvertakeEarlierResponse() throws Exception {
    handler.expectRequests(1);

    try (Socket socket = connect()) {
      socket.getOutputStream().write(request(FIRST_PATH).getBytes(US_ASCII));
      socket.getOutputStream().flush();

      assertTrue(handler.awaitAllRequestsReceived(), "server did not receive first request");

      CountDownLatch blockedRequestSeen = enableAppSecRequestBlockingFor(SECOND_PATH);
      socket.getOutputStream().write(request(SECOND_PATH).getBytes(US_ASCII));
      socket.getOutputStream().flush();

      assertTrue(
          blockedRequestSeen.await(5, SECONDS), "server did not block second pipelined request");

      handler.writeResponses();

      assertEquals("response " + FIRST_PATH, readHttpResponseBody(socket.getInputStream()));
      assertTrue(
          readHeaders(socket.getInputStream()).startsWith("HTTP/1.1 403 "),
          "second response should be the deferred blocking response");
    }
  }

  @Test
  void requestBlockOnLaterPipelinedRequestWaitsForEarlierChunkedResponseCompletion()
      throws Exception {
    handler.expectRequests(1);

    try (Socket socket = connect()) {
      socket.getOutputStream().write(request(FIRST_PATH).getBytes(US_ASCII));
      socket.getOutputStream().flush();

      assertTrue(handler.awaitAllRequestsReceived(), "server did not receive first request");

      CountDownLatch blockedRequestSeen = enableAppSecRequestBlockingFor(SECOND_PATH);
      socket.getOutputStream().write(request(SECOND_PATH).getBytes(US_ASCII));
      socket.getOutputStream().flush();

      assertTrue(
          blockedRequestSeen.await(5, SECONDS), "server did not block second pipelined request");

      handler.writeChunkedResponse();

      assertEquals("response " + FIRST_PATH, readChunkedHttpResponseBody(socket.getInputStream()));
      assertTrue(
          readHeaders(socket.getInputStream()).startsWith("HTTP/1.1 403 "),
          "second response should be the deferred blocking response");
    }
  }

  @Test
  void requestBlockOnLaterPipelinedRequestFollowsEarlierHeaderOnlyResponse() throws Exception {
    handler.expectRequests(1);

    try (Socket socket = connect()) {
      socket.getOutputStream().write(request(FIRST_PATH).getBytes(US_ASCII));
      socket.getOutputStream().flush();

      assertTrue(handler.awaitAllRequestsReceived(), "server did not receive first request");

      CountDownLatch blockedRequestSeen = enableAppSecRequestBlockingFor(SECOND_PATH);
      socket.getOutputStream().write(request(SECOND_PATH).getBytes(US_ASCII));
      socket.getOutputStream().flush();

      assertTrue(
          blockedRequestSeen.await(5, SECONDS), "server did not block second pipelined request");

      handler.writeHeaderOnlyResponse();

      assertTrue(
          readHeaders(socket.getInputStream()).startsWith("HTTP/1.1 204 "),
          "first response should be the header-only response");
      assertTrue(
          readHeaders(socket.getInputStream()).startsWith("HTTP/1.1 403 "),
          "second response should be the deferred blocking response");
    }
  }

  @Test
  void requestBlockOnLaterPipelinedRequestFollowsEarlierHeadResponse() throws Exception {
    handler.expectRequests(1);

    try (Socket socket = connect()) {
      socket.getOutputStream().write(headRequest(FIRST_PATH).getBytes(US_ASCII));
      socket.getOutputStream().flush();

      assertTrue(handler.awaitAllRequestsReceived(), "server did not receive first request");

      CountDownLatch blockedRequestSeen = enableAppSecRequestBlockingFor(SECOND_PATH);
      socket.getOutputStream().write(request(SECOND_PATH).getBytes(US_ASCII));
      socket.getOutputStream().flush();

      assertTrue(
          blockedRequestSeen.await(5, SECONDS), "server did not block second pipelined request");

      handler.writeHeadResponse();

      assertTrue(
          readHeaders(socket.getInputStream()).startsWith("HTTP/1.1 200 "),
          "first response should be the HEAD response");
      assertTrue(
          readHeaders(socket.getInputStream()).startsWith("HTTP/1.1 403 "),
          "second response should be the deferred blocking response");
    }
  }

  @Test
  void lastContentAfterInterimResponseDoesNotCompleteServerSpan() throws Exception {
    handler.expectRequests(1);

    try (Socket socket = connect()) {
      socket.getOutputStream().write(request(FIRST_PATH).getBytes(US_ASCII));
      socket.getOutputStream().flush();

      assertTrue(handler.awaitAllRequestsReceived(), "server did not receive first request");

      handler.writeInterimResponseWithTerminatorThenResponse();

      assertTrue(
          readHeaders(socket.getInputStream()).startsWith("HTTP/1.1 100 "),
          "first response should be the interim response");
      assertEquals("response " + FIRST_PATH, readHttpResponseBody(socket.getInputStream()));
    }
  }

  @Test
  void requestBlockOnLaterPipelinedRequestWaitsForEarlierEarlyHintsResponseCompletion()
      throws Exception {
    handler.expectRequests(1);

    try (Socket socket = connect()) {
      socket.getOutputStream().write(request(FIRST_PATH).getBytes(US_ASCII));
      socket.getOutputStream().flush();

      assertTrue(handler.awaitAllRequestsReceived(), "server did not receive first request");

      CountDownLatch blockedRequestSeen = enableAppSecRequestBlockingFor(SECOND_PATH);
      socket.getOutputStream().write(request(SECOND_PATH).getBytes(US_ASCII));
      socket.getOutputStream().flush();

      assertTrue(
          blockedRequestSeen.await(5, SECONDS), "server did not block second pipelined request");

      handler.writeEarlyHintsWithTerminatorThenResponse();

      assertTrue(
          readHeaders(socket.getInputStream()).startsWith("HTTP/1.1 103 "),
          "first response should be the early hints response");
      assertEquals("response " + FIRST_PATH, readHttpResponseBody(socket.getInputStream()));
      assertTrue(
          readHeaders(socket.getInputStream()).startsWith("HTTP/1.1 403 "),
          "second response should be the deferred blocking response");
    }
  }

  @Test
  void blockResponseFunctionOnLaterPipelinedRequestDoesNotOvertakeEarlierResponse()
      throws Exception {
    enableAppSec();
    handler.expectRequests(1);

    try (Socket socket = connect()) {
      socket.getOutputStream().write(request(FIRST_PATH).getBytes(US_ASCII));
      socket.getOutputStream().flush();

      assertTrue(handler.awaitAllRequestsReceived(), "server did not receive first request");

      CountDownLatch blockedRequestSeen = handler.blockWithResponseFunctionFor(SECOND_PATH);
      socket.getOutputStream().write(request(SECOND_PATH).getBytes(US_ASCII));
      socket.getOutputStream().flush();

      assertTrue(
          blockedRequestSeen.await(5, SECONDS), "server did not block second pipelined request");

      handler.writeResponses();

      assertEquals("response " + FIRST_PATH, readHttpResponseBody(socket.getInputStream()));
      assertTrue(
          readHeaders(socket.getInputStream()).startsWith("HTTP/1.1 403 "),
          "second response should be the deferred blocking response");
    }
  }

  private static String pipelinedRequests() {
    return "GET "
        + FIRST_PATH
        + " HTTP/1.1\r\nHost: localhost\r\n\r\n"
        + "GET "
        + SECOND_PATH
        + " HTTP/1.1\r\nHost: localhost\r\n\r\n"
        + "GET "
        + THIRD_PATH
        + " HTTP/1.1\r\nHost: localhost\r\n\r\n";
  }

  private static String request(String path) {
    return "GET " + path + " HTTP/1.1\r\nHost: localhost\r\n\r\n";
  }

  private static String headRequest(String path) {
    return "HEAD " + path + " HTTP/1.1\r\nHost: localhost\r\n\r\n";
  }

  private CountDownLatch enableAppSecRequestBlockingFor(String blockedPath) {
    SubscriptionService subscriptions = (SubscriptionService) enableAppSec();
    CountDownLatch blockedRequestSeen = new CountDownLatch(1);

    subscriptions.registerCallback(
        EVENTS.requestMethodUriRaw(),
        new TriFunction<RequestContext, String, URIDataAdapter, Flow<Void>>() {
          @Override
          public Flow<Void> apply(
              RequestContext requestContext, String method, URIDataAdapter uri) {
            if (!blockedPath.equals(uri.path())) {
              return Flow.ResultFlow.empty();
            }
            blockedRequestSeen.countDown();
            return new Flow.ResultFlow<Void>(null) {
              @Override
              public Action getAction() {
                return new Action.RequestBlockingAction(403, BlockingContentType.NONE);
              }
            };
          }
        });
    return blockedRequestSeen;
  }

  private Object enableAppSec() {
    SubscriptionService subscriptions =
        (SubscriptionService) AgentTracer.get().getSubscriptionService(RequestContextSlot.APPSEC);
    appSecSubscriptions = subscriptions;
    originalAppSecActive = ActiveSubsystems.APPSEC_ACTIVE;
    ActiveSubsystems.APPSEC_ACTIVE = true;

    subscriptions.registerCallback(
        EVENTS.requestStarted(),
        new Supplier<Flow<Object>>() {
          @Override
          public Flow<Object> get() {
            return new Flow.ResultFlow<>(new Object());
          }
        });
    return subscriptions;
  }

  private static TraceMatcher serverTrace(String path) {
    return trace(
        span()
            .root()
            .operationName(Pattern.compile("netty\\.request"))
            .resourceName(Pattern.compile("GET " + Pattern.quote(path)))
            .type("web"));
  }

  @ChannelHandler.Sharable
  private static final class PipeliningHandler
      extends SimpleChannelInboundHandler<FullHttpRequest> {
    private volatile CountDownLatch receivedRequests;
    private final List<String> paths = new ArrayList<>();
    private volatile ChannelHandlerContext context;
    private volatile String blockResponseFunctionPath;
    private volatile CountDownLatch blockResponseFunctionRequestSeen;

    private PipeliningHandler() {
      super(false);
      expectRequests(0);
    }

    private void expectRequests(int expectedRequests) {
      receivedRequests = new CountDownLatch(expectedRequests);
      context = null;
      synchronized (paths) {
        paths.clear();
      }
    }

    private CountDownLatch blockWithResponseFunctionFor(String path) {
      blockResponseFunctionPath = path;
      blockResponseFunctionRequestSeen = new CountDownLatch(1);
      return blockResponseFunctionRequestSeen;
    }

    private void clearBlockResponseFunctionBlocking() {
      blockResponseFunctionPath = null;
      blockResponseFunctionRequestSeen = null;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
      context = ctx;
      boolean blockingResponseCommitted = false;
      synchronized (paths) {
        paths.add(request.uri());
      }
      if (request.uri().equals(blockResponseFunctionPath)) {
        AgentSpan span = AgentTracer.activeSpan();
        RequestContext requestContext = span == null ? null : span.getRequestContext();
        BlockResponseFunction blockResponseFunction =
            requestContext == null ? null : requestContext.getBlockResponseFunction();
        if (blockResponseFunction != null) {
          blockingResponseCommitted =
              blockResponseFunction.tryCommitBlockingResponse(
                  requestContext.getTraceSegment(),
                  403,
                  BlockingContentType.NONE,
                  emptyMap(),
                  null);
        }
        if (blockingResponseCommitted && blockResponseFunctionRequestSeen != null) {
          blockResponseFunctionRequestSeen.countDown();
        }
      }
      receivedRequests.countDown();
      if (!blockingResponseCommitted) {
        ReferenceCountUtil.release(request);
      }
    }

    private boolean awaitAllRequestsReceived() throws InterruptedException {
      return receivedRequests.await(5, SECONDS);
    }

    private void writeResponses() {
      ChannelHandlerContext responseContext = context;
      if (responseContext == null) {
        throw new IllegalStateException("no request context captured");
      }
      List<String> responsePaths;
      synchronized (paths) {
        responsePaths = new ArrayList<>(paths);
      }
      responseContext
          .executor()
          .execute(
              () -> {
                for (String path : responsePaths) {
                  byte[] body = ("response " + path).getBytes(UTF_8);
                  DefaultFullHttpResponse response =
                      new DefaultFullHttpResponse(HTTP_1_1, OK, Unpooled.wrappedBuffer(body));
                  response.headers().set(CONTENT_LENGTH, body.length);
                  responseContext.write(response);
                }
                responseContext.flush();
              });
    }

    private void writeChunkedResponse() {
      ChannelHandlerContext responseContext = context;
      if (responseContext == null) {
        throw new IllegalStateException("no request context captured");
      }
      String path;
      synchronized (paths) {
        path = paths.get(0);
      }
      responseContext
          .executor()
          .execute(
              () -> {
                byte[] body = ("response " + path).getBytes(UTF_8);
                DefaultHttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
                response.headers().set(TRANSFER_ENCODING, CHUNKED);
                responseContext.write(response);
                responseContext.write(new DefaultHttpContent(Unpooled.wrappedBuffer(body)));
                responseContext.write(new DefaultLastHttpContent(Unpooled.EMPTY_BUFFER));
                responseContext.flush();
              });
    }

    private void writeHeaderOnlyResponse() {
      ChannelHandlerContext responseContext = context;
      if (responseContext == null) {
        throw new IllegalStateException("no request context captured");
      }
      responseContext
          .executor()
          .execute(
              () -> {
                DefaultHttpResponse response = new DefaultHttpResponse(HTTP_1_1, NO_CONTENT);
                response.headers().set(CONNECTION, KEEP_ALIVE);
                responseContext.write(response);
                responseContext.flush();
              });
    }

    private void writeHeadResponse() {
      ChannelHandlerContext responseContext = context;
      if (responseContext == null) {
        throw new IllegalStateException("no request context captured");
      }
      String path;
      synchronized (paths) {
        path = paths.get(0);
      }
      responseContext
          .executor()
          .execute(
              () -> {
                byte[] body = ("response " + path).getBytes(UTF_8);
                DefaultHttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
                response.headers().set(CONTENT_LENGTH, body.length);
                responseContext.write(response);
                responseContext.flush();
              });
    }

    private void writeInterimResponseWithTerminatorThenResponse() {
      ChannelHandlerContext responseContext = context;
      if (responseContext == null) {
        throw new IllegalStateException("no request context captured");
      }
      String path;
      synchronized (paths) {
        path = paths.get(0);
      }
      responseContext
          .executor()
          .execute(
              () -> {
                responseContext.write(new DefaultHttpResponse(HTTP_1_1, CONTINUE));
                responseContext.write(new DefaultLastHttpContent(Unpooled.EMPTY_BUFFER));

                byte[] body = ("response " + path).getBytes(UTF_8);
                DefaultFullHttpResponse response =
                    new DefaultFullHttpResponse(HTTP_1_1, OK, Unpooled.wrappedBuffer(body));
                response.headers().set(CONTENT_LENGTH, body.length);
                responseContext.write(response);
                responseContext.flush();
              });
    }

    private void writeEarlyHintsWithTerminatorThenResponse() {
      writeInformationalResponseWithTerminatorThenResponse(EARLY_HINTS);
    }

    private void writeInformationalResponseWithTerminatorThenResponse(HttpResponseStatus status) {
      ChannelHandlerContext responseContext = context;
      if (responseContext == null) {
        throw new IllegalStateException("no request context captured");
      }
      String path;
      synchronized (paths) {
        path = paths.get(0);
      }
      responseContext
          .executor()
          .execute(
              () -> {
                responseContext.write(new DefaultHttpResponse(HTTP_1_1, status));
                responseContext.write(new DefaultLastHttpContent(Unpooled.EMPTY_BUFFER));

                byte[] body = ("response " + path).getBytes(UTF_8);
                DefaultFullHttpResponse response =
                    new DefaultFullHttpResponse(HTTP_1_1, OK, Unpooled.wrappedBuffer(body));
                response.headers().set(CONTENT_LENGTH, body.length);
                responseContext.write(response);
                responseContext.flush();
              });
    }
  }
}
