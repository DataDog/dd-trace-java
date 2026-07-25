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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.trace.agent.test.AbstractInstrumentationTest;
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
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.util.ReferenceCountUtil;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class NettyHttp11PipeliningTest extends AbstractInstrumentationTest {

  private static final String FIRST_PATH = "/pipelined/first";
  private static final String SECOND_PATH = "/pipelined/second";
  private static final String THIRD_PATH = "/pipelined/third";
  private static final HttpResponseStatus EARLY_HINTS = new HttpResponseStatus(103, "Early Hints");

  private EventLoopGroup eventLoopGroup;
  private DecodedRequestObserver decodedRequestObserver;
  private PipeliningHandler handler;
  private int port;
  private Object appSecSubscriptions;
  private boolean originalAppSecActive;

  @BeforeAll
  void startServer() throws Exception {
    eventLoopGroup = new NioEventLoopGroup();
    decodedRequestObserver = new DecodedRequestObserver();
    handler = new PipeliningHandler();
    ServerBootstrap bootstrap =
        new ServerBootstrap()
            .group(eventLoopGroup)
            .channel(NioServerSocketChannel.class)
            .childHandler(
                new ChannelInitializer<Channel>() {
                  @Override
                  protected void initChannel(Channel ch) {
                    HttpServerCodec codec = new HttpServerCodec();
                    ch.pipeline().addLast(codec);
                    ch.pipeline()
                        .addAfter(
                            ch.pipeline().context(codec).name(),
                            "decoded-request-observer",
                            decodedRequestObserver);
                    ch.pipeline().addLast(new HttpObjectAggregator(65536));
                    ch.pipeline().addLast(handler);
                  }
                });
    Channel channel = bootstrap.bind(0).sync().channel();
    port = ((InetSocketAddress) channel.localAddress()).getPort();
  }

  @AfterAll
  void stopServer() {
    if (eventLoopGroup != null) {
      eventLoopGroup.shutdownGracefully();
    }
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
    try (Socket socket = new Socket("localhost", port)) {
      socket.setSoTimeout(5000);
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
  void reactorNettyCompletesPipelinedFixedLengthResponses() throws Exception {
    DisposableServer reactorServer =
        HttpServer.create()
            .host("localhost")
            .port(0)
            .handle(
                (request, response) -> {
                  String body = "response " + request.uri();
                  response.header(CONTENT_LENGTH, Integer.toString(body.getBytes(UTF_8).length));
                  return Mono.delay(Duration.ofMillis(100))
                      .then(Mono.defer(() -> response.sendString(Mono.just(body)).then()));
                })
            .bindNow();

    try {
      try (Socket socket = new Socket("localhost", reactorServer.port())) {
        socket.setSoTimeout(5000);
        socket.getOutputStream().write(pipelinedRequests().getBytes(US_ASCII));
        socket.getOutputStream().flush();

        assertEquals("response " + FIRST_PATH, readHttpResponseBody(socket.getInputStream()));
        assertEquals("response " + SECOND_PATH, readHttpResponseBody(socket.getInputStream()));
        assertEquals("response " + THIRD_PATH, readHttpResponseBody(socket.getInputStream()));
      }

      assertTraces(
          SORT_BY_START_TIME,
          serverTrace(FIRST_PATH),
          serverTrace(SECOND_PATH),
          serverTrace(THIRD_PATH));
    } finally {
      reactorServer.disposeNow();
    }
  }

  @Test
  void requestBlockOnLaterPipelinedRequestDoesNotOvertakeEarlierResponse() throws Exception {
    handler.expectRequests(1);

    try (Socket socket = new Socket("localhost", port)) {
      socket.setSoTimeout(5000);
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
          readHttpResponseHeaders(socket.getInputStream()).startsWith("HTTP/1.1 403 "),
          "second response should be the deferred blocking response");
    }
  }

  @Test
  void additionalPipelinedRequestsBehindDeferredBlockAreIgnored() throws Exception {
    decodedRequestObserver.expectRequests(3);
    handler.expectRequests(1);

    try (Socket socket = new Socket("localhost", port)) {
      socket.setSoTimeout(5000);
      socket.getOutputStream().write(request(FIRST_PATH).getBytes(US_ASCII));
      socket.getOutputStream().flush();

      assertTrue(handler.awaitAllRequestsReceived(), "server did not receive first request");

      CountDownLatch blockedRequestSeen = enableAppSecRequestBlockingFor(SECOND_PATH, THIRD_PATH);
      socket
          .getOutputStream()
          .write((request(SECOND_PATH) + request(THIRD_PATH)).getBytes(US_ASCII));
      socket.getOutputStream().flush();

      assertTrue(
          blockedRequestSeen.await(5, SECONDS), "server did not block second pipelined request");
      assertTrue(
          decodedRequestObserver.awaitAllRequests(),
          "server did not decode all three pipelined requests before responding");

      handler.writeResponses();

      assertEquals("response " + FIRST_PATH, readHttpResponseBody(socket.getInputStream()));
      assertTrue(
          readHttpResponseHeaders(socket.getInputStream()).startsWith("HTTP/1.1 403 "),
          "second response should be the deferred blocking response");
      assertNull(
          handler.inboundException,
          "additional pipelined requests should be swallowed by the existing blocking handler");
    }
  }

  @Test
  void requestBlockOnLaterPipelinedRequestWaitsForEarlierChunkedResponseCompletion()
      throws Exception {
    handler.expectRequests(1);

    try (Socket socket = new Socket("localhost", port)) {
      socket.setSoTimeout(5000);
      socket.getOutputStream().write(request(FIRST_PATH).getBytes(US_ASCII));
      socket.getOutputStream().flush();

      assertTrue(handler.awaitAllRequestsReceived(), "server did not receive first request");

      CountDownLatch blockedRequestSeen = enableAppSecRequestBlockingFor(SECOND_PATH);
      socket.getOutputStream().write(request(SECOND_PATH).getBytes(US_ASCII));
      socket.getOutputStream().flush();

      assertTrue(
          blockedRequestSeen.await(5, SECONDS), "server did not block second pipelined request");

      handler.writeChunkedResponse();

      assertEquals("response " + FIRST_PATH, readHttpChunkedResponseBody(socket.getInputStream()));
      assertTrue(
          readHttpResponseHeaders(socket.getInputStream()).startsWith("HTTP/1.1 403 "),
          "second response should be the deferred blocking response");
    }
  }

  @Test
  void requestBlockOnLaterPipelinedRequestFollowsEarlierHeaderOnlyResponse() throws Exception {
    handler.expectRequests(1);

    try (Socket socket = new Socket("localhost", port)) {
      socket.setSoTimeout(5000);
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
          readHttpResponseHeaders(socket.getInputStream()).startsWith("HTTP/1.1 204 "),
          "first response should be the header-only response");
      assertTrue(
          readHttpResponseHeaders(socket.getInputStream()).startsWith("HTTP/1.1 403 "),
          "second response should be the deferred blocking response");
    }
  }

  @Test
  void requestBlockOnLaterPipelinedRequestFollowsEarlierHeadResponse() throws Exception {
    handler.expectRequests(1);

    try (Socket socket = new Socket("localhost", port)) {
      socket.setSoTimeout(5000);
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
          readHttpResponseHeaders(socket.getInputStream()).startsWith("HTTP/1.1 200 "),
          "first response should be the HEAD response");
      assertTrue(
          readHttpResponseHeaders(socket.getInputStream()).startsWith("HTTP/1.1 403 "),
          "second response should be the deferred blocking response");
    }
  }

  @Test
  void lastContentAfterInterimResponseDoesNotCompleteServerSpan() throws Exception {
    handler.expectRequests(1);

    try (Socket socket = new Socket("localhost", port)) {
      socket.setSoTimeout(5000);
      socket.getOutputStream().write(request(FIRST_PATH).getBytes(US_ASCII));
      socket.getOutputStream().flush();

      assertTrue(handler.awaitAllRequestsReceived(), "server did not receive first request");

      handler.writeInterimResponseWithTerminatorThenResponse();

      assertTrue(
          readHttpResponseHeaders(socket.getInputStream()).startsWith("HTTP/1.1 100 "),
          "first response should be the interim response");
      assertEquals("response " + FIRST_PATH, readHttpResponseBody(socket.getInputStream()));
    }
  }

  @Test
  void requestBlockOnLaterPipelinedRequestWaitsForEarlierEarlyHintsResponseCompletion()
      throws Exception {
    handler.expectRequests(1);

    try (Socket socket = new Socket("localhost", port)) {
      socket.setSoTimeout(5000);
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
          readHttpResponseHeaders(socket.getInputStream()).startsWith("HTTP/1.1 103 "),
          "first response should be the early hints response");
      assertEquals("response " + FIRST_PATH, readHttpResponseBody(socket.getInputStream()));
      assertTrue(
          readHttpResponseHeaders(socket.getInputStream()).startsWith("HTTP/1.1 403 "),
          "second response should be the deferred blocking response");
    }
  }

  @Test
  void blockResponseFunctionOnLaterPipelinedRequestDoesNotOvertakeEarlierResponse()
      throws Exception {
    enableAppSec();
    handler.expectRequests(1);

    try (Socket socket = new Socket("localhost", port)) {
      socket.setSoTimeout(5000);
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
          readHttpResponseHeaders(socket.getInputStream()).startsWith("HTTP/1.1 403 "),
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

  private CountDownLatch enableAppSecRequestBlockingFor(String... blockedPaths) {
    SubscriptionService subscriptions = (SubscriptionService) enableAppSec();
    CountDownLatch blockedRequestSeen = new CountDownLatch(1);

    subscriptions.registerCallback(
        EVENTS.requestMethodUriRaw(),
        new TriFunction<RequestContext, String, URIDataAdapter, Flow<Void>>() {
          @Override
          public Flow<Void> apply(
              RequestContext requestContext, String method, URIDataAdapter uri) {
            if (!Arrays.asList(blockedPaths).contains(uri.path())) {
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

  private static String readHttpResponseBody(InputStream in) throws IOException {
    String headers = readHttpResponseHeaders(in);
    assertTrue(headers.startsWith("HTTP/1.1 200 "), "unexpected response: " + headers);
    int contentLength = contentLength(headers);
    byte[] body = new byte[contentLength];
    int read = 0;
    while (read < contentLength) {
      int count = in.read(body, read, contentLength - read);
      if (count == -1) {
        throw new EOFException("response ended before body was complete");
      }
      read += count;
    }
    return new String(body, UTF_8);
  }

  private static String readHttpChunkedResponseBody(InputStream in) throws IOException {
    String headers = readHttpResponseHeaders(in);
    assertTrue(headers.startsWith("HTTP/1.1 200 "), "unexpected response: " + headers);

    ByteArrayOutputStream body = new ByteArrayOutputStream();
    while (true) {
      String chunkSizeLine = readHttpLine(in);
      int chunkSize = Integer.parseInt(chunkSizeLine, 16);
      if (chunkSize == 0) {
        String trailer;
        do {
          trailer = readHttpLine(in);
        } while (!trailer.isEmpty());
        return body.toString(UTF_8.name());
      }
      byte[] chunk = new byte[chunkSize];
      int read = 0;
      while (read < chunkSize) {
        int count = in.read(chunk, read, chunkSize - read);
        if (count == -1) {
          throw new EOFException("response ended before chunk was complete");
        }
        read += count;
      }
      body.write(chunk);
      String chunkTerminator = readHttpLine(in);
      assertEquals("", chunkTerminator, "chunk was not followed by CRLF");
    }
  }

  private static String readHttpResponseHeaders(InputStream in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int state = 0;
    while (state < 4) {
      int b = in.read();
      if (b == -1) {
        throw new EOFException("response ended before headers were complete");
      }
      out.write(b);
      if ((state == 0 || state == 2) && b == '\r') {
        state++;
      } else if ((state == 1 || state == 3) && b == '\n') {
        state++;
      } else {
        state = b == '\r' ? 1 : 0;
      }
    }
    return out.toString(US_ASCII.name());
  }

  private static String readHttpLine(InputStream in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int previous = -1;
    while (true) {
      int current = in.read();
      if (current == -1) {
        throw new EOFException("response ended before line was complete");
      }
      if (previous == '\r' && current == '\n') {
        byte[] line = out.toByteArray();
        return new String(line, 0, line.length - 1, US_ASCII);
      }
      out.write(current);
      previous = current;
    }
  }

  private static int contentLength(String headers) {
    for (String line : headers.split("\r\n")) {
      int separator = line.indexOf(':');
      if (separator > 0 && "content-length".equalsIgnoreCase(line.substring(0, separator))) {
        return Integer.parseInt(line.substring(separator + 1).trim());
      }
    }
    throw new AssertionError("missing content-length header: " + headers);
  }

  @ChannelHandler.Sharable
  private static final class DecodedRequestObserver extends ChannelInboundHandlerAdapter {
    private volatile CountDownLatch receivedRequests = new CountDownLatch(0);

    private void expectRequests(int expectedRequests) {
      receivedRequests = new CountDownLatch(expectedRequests);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
      if (msg instanceof HttpRequest) {
        receivedRequests.countDown();
      }
      ctx.fireChannelRead(msg);
    }

    private boolean awaitAllRequests() throws InterruptedException {
      return receivedRequests.await(5, SECONDS);
    }
  }

  @ChannelHandler.Sharable
  private static final class PipeliningHandler
      extends SimpleChannelInboundHandler<FullHttpRequest> {
    private volatile CountDownLatch receivedRequests;
    private final List<String> paths = new ArrayList<>();
    private volatile ChannelHandlerContext context;
    private volatile String blockResponseFunctionPath;
    private volatile CountDownLatch blockResponseFunctionRequestSeen;
    private volatile Throwable inboundException;

    private PipeliningHandler() {
      super(false);
      expectRequests(0);
    }

    private void expectRequests(int expectedRequests) {
      receivedRequests = new CountDownLatch(expectedRequests);
      context = null;
      inboundException = null;
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

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
      inboundException = cause;
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
                responseContext.write(new DefaultLastHttpContent(Unpooled.EMPTY_BUFFER));
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
                responseContext.write(new DefaultLastHttpContent(Unpooled.EMPTY_BUFFER));
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
