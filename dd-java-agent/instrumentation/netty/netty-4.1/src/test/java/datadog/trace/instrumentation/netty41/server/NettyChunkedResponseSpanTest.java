package datadog.trace.instrumentation.netty41.server;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static io.netty.handler.codec.http.HttpHeaderNames.CONNECTION;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaderNames.TRANSFER_ENCODING;
import static io.netty.handler.codec.http.HttpHeaderNames.UPGRADE;
import static io.netty.handler.codec.http.HttpHeaderValues.CHUNKED;
import static io.netty.handler.codec.http.HttpResponseStatus.CONTINUE;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_MODIFIED;
import static io.netty.handler.codec.http.HttpResponseStatus.NO_CONTENT;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpResponseStatus.RESET_CONTENT;
import static io.netty.handler.codec.http.HttpResponseStatus.SWITCHING_PROTOCOLS;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.test.assertions.SpanMatcher;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.LastHttpContent;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class NettyChunkedResponseSpanTest extends NettyHttpServerTestSupport {

  private static final String PATH = "/chunked";
  private static final String CONTINUE_PATH = "/chunked/continue";
  private static final String NO_CONTENT_PATH = "/bodyless/no-content";
  private static final String RESET_CONTENT_PATH = "/bodyless/reset-content";
  private static final String NOT_MODIFIED_PATH = "/bodyless/not-modified";
  private static final String CONTENT_LENGTH_ZERO_PATH = "/bodyless/content-length-zero";
  private static final String HEAD_PATH = "/bodyless/head";
  private static final String NO_RESPONSE_PATH = "/no-response";
  private static final String CLOSE_DELIMITED_PATH = "/close-delimited";
  private static final String CLOSE_DELIMITED_FULL_RESPONSE_PATH = "/close-delimited/full";
  private static final String WEBSOCKET_PATH = "/websocket";
  private static final String CONNECT_AUTHORITY = "example.com:443";
  private static final String EARLY_HINTS_PATH = "/chunked/early-hints";
  private static final HttpResponseStatus EARLY_HINTS = new HttpResponseStatus(103, "Early Hints");

  private final ChunkedResponseHandler handler = new ChunkedResponseHandler();

  @Override
  protected void configurePipeline(Channel ch) {
    ch.pipeline().addLast(new HttpServerCodec());
    ch.pipeline().addLast(handler);
  }

  @Test
  void keepsServerSpanOpenUntilLastResponseChunk() throws Exception {
    boolean reportedBeforeLastChunk;
    try (Socket socket = connect()) {
      socket.getOutputStream().write(request().getBytes(US_ASCII));
      socket.getOutputStream().flush();

      ChannelHandlerContext responseContext = handler.awaitFirstChunkWritten();
      reportedBeforeLastChunk = writer.waitForTracesMax(1, 1);

      writeLastChunk(responseContext);
      assertEquals("first", readChunkedHttpResponseBody(socket.getInputStream()));
    }

    assertFalse(
        reportedBeforeLastChunk, "server span should not be reported before LastHttpContent");
    assertTraces(trace(serverSpan(PATH)));
  }

  @Test
  void keepsServerSpanOpenUntilConnectionDrops() throws Exception {
    boolean reportedBeforeConnectionDrop;
    try (Socket socket = connect()) {
      socket.getOutputStream().write(request().getBytes(US_ASCII));
      socket.getOutputStream().flush();

      ChannelHandlerContext responseContext = handler.awaitFirstChunkWritten();
      reportedBeforeConnectionDrop = writer.waitForTracesMax(1, 1);

      closeChannel(responseContext);
    }

    assertFalse(
        reportedBeforeConnectionDrop, "server span should not be reported before connection drop");
    assertTraces(trace(serverSpan(PATH).error()));
  }

  @Test
  void finishesServerSpanWithoutErrorForCloseDelimitedResponse() throws Exception {
    boolean reportedBeforeConnectionClose;
    try (Socket socket = connect()) {
      socket.getOutputStream().write(request(CLOSE_DELIMITED_PATH).getBytes(US_ASCII));
      socket.getOutputStream().flush();

      ChannelHandlerContext responseContext = handler.awaitFirstChunkWritten();
      reportedBeforeConnectionClose = writer.waitForTracesMax(1, 1);

      closeChannel(responseContext);
    }

    assertFalse(
        reportedBeforeConnectionClose,
        "server span should not be reported before the connection closes");
    // The response has neither Content-Length nor chunked encoding, so closing the connection is
    // its normal completion and the span must not be flagged as an error.
    assertTraces(trace(serverSpan(CLOSE_DELIMITED_PATH)));
  }

  @Test
  void waitsForChannelCloseForCloseDelimitedFullResponse() throws Exception {
    boolean reportedBeforeConnectionClose;
    try (Socket socket = connect()) {
      socket
          .getOutputStream()
          .write(request(CLOSE_DELIMITED_FULL_RESPONSE_PATH).getBytes(US_ASCII));
      socket.getOutputStream().flush();

      ChannelHandlerContext responseContext = handler.awaitCloseDelimitedFullResponseWritten();
      reportedBeforeConnectionClose = writer.waitForTracesMax(1, 1);

      closeChannel(responseContext);
    }

    assertFalse(
        reportedBeforeConnectionClose,
        "server span should not be reported before the connection closes");
    assertTraces(trace(serverSpan(CLOSE_DELIMITED_FULL_RESPONSE_PATH)));
  }

  @Test
  void closesServerSpanWithoutErrorWhenConnectionDropsBeforeResponseStarts() throws Exception {
    boolean reportedBeforeConnectionDrop;
    try (Socket socket = connect()) {
      socket.getOutputStream().write(request(NO_RESPONSE_PATH).getBytes(US_ASCII));
      socket.getOutputStream().flush();

      handler.awaitRequestWithoutResponse();
      reportedBeforeConnectionDrop = writer.waitForTracesMax(1, 1);
    }

    assertFalse(
        reportedBeforeConnectionDrop, "server span should not be reported before connection drop");
    assertTraces(trace(serverSpan(NO_RESPONSE_PATH)));
  }

  @Test
  void keepsServerSpansSeparateForSequentialKeepAliveChunkedResponses() throws Exception {
    boolean firstReportedBeforeLastChunk;
    boolean secondReportedBeforeLastChunk;
    try (Socket socket = connect()) {
      socket.getOutputStream().write(request().getBytes(US_ASCII));
      socket.getOutputStream().flush();

      ChannelHandlerContext firstResponseContext = handler.awaitFirstChunkWritten();
      firstReportedBeforeLastChunk = writer.waitForTracesMax(1, 1);

      writeLastChunk(firstResponseContext);
      assertEquals("first", readChunkedHttpResponseBody(socket.getInputStream()));
      assertTrue(writer.waitForTracesMax(1, 5), "first keep-alive response was not reported");

      socket.getOutputStream().write(request().getBytes(US_ASCII));
      socket.getOutputStream().flush();

      ChannelHandlerContext secondResponseContext = handler.awaitFirstChunkWritten();
      secondReportedBeforeLastChunk = writer.waitForTracesMax(2, 1);

      writeLastChunk(secondResponseContext);
      assertEquals("first", readChunkedHttpResponseBody(socket.getInputStream()));
    }

    assertFalse(
        firstReportedBeforeLastChunk,
        "first server span should not be reported before LastHttpContent");
    assertFalse(
        secondReportedBeforeLastChunk,
        "second server span should not be reported before LastHttpContent");
    assertTraces(trace(serverSpan(PATH)), trace(serverSpan(PATH)));
  }

  @Test
  void keepsServerSpanOpenAfter100ContinueUntilLastResponseChunk() throws Exception {
    boolean reportedAfter100Continue;
    boolean reportedBeforeLastChunk;
    try (Socket socket = connect()) {
      socket.getOutputStream().write(request(CONTINUE_PATH).getBytes(US_ASCII));
      socket.getOutputStream().flush();

      ChannelHandlerContext responseContext = handler.await100ContinueWritten();
      assertTrue(
          readHeaders(socket.getInputStream()).startsWith("HTTP/1.1 100 "),
          "server did not write 100 Continue");
      reportedAfter100Continue = writer.waitForTracesMax(1, 1);

      writeFirstChunk(responseContext);
      responseContext = handler.awaitFirstChunkWritten();
      reportedBeforeLastChunk = writer.waitForTracesMax(1, 1);

      writeLastChunk(responseContext);
      assertEquals("first", readChunkedHttpResponseBody(socket.getInputStream()));
    }

    assertFalse(reportedAfter100Continue, "server span should not be reported after 100 Continue");
    assertFalse(
        reportedBeforeLastChunk, "server span should not be reported before LastHttpContent");
    assertTraces(trace(serverSpan(CONTINUE_PATH)));
  }

  @Test
  void keepsServerSpanOpenAfter103EarlyHintsUntilLastResponseChunk() throws Exception {
    boolean reportedAfterEarlyHints;
    boolean reportedBeforeLastChunk;
    try (Socket socket = connect()) {
      socket.getOutputStream().write(request(EARLY_HINTS_PATH).getBytes(US_ASCII));
      socket.getOutputStream().flush();

      ChannelHandlerContext responseContext = handler.awaitEarlyHintsWritten();
      assertResponseStatus(readHeaders(socket.getInputStream()), EARLY_HINTS);
      reportedAfterEarlyHints = writer.waitForTracesMax(1, 1);

      writeFirstChunk(responseContext);
      responseContext = handler.awaitFirstChunkWritten();
      reportedBeforeLastChunk = writer.waitForTracesMax(1, 1);

      writeLastChunk(responseContext);
      assertEquals("first", readChunkedHttpResponseBody(socket.getInputStream()));
    }

    assertFalse(reportedAfterEarlyHints, "server span should not be reported after 103");
    assertFalse(
        reportedBeforeLastChunk, "server span should not be reported before LastHttpContent");
    assertTraces(trace(serverSpan(EARLY_HINTS_PATH)));
  }

  @Test
  void finishesServerSpanForHeaderOnlyNoContentResponse() throws Exception {
    assertHeaderOnlyResponseFinishes(NO_CONTENT_PATH, NO_CONTENT);
  }

  @Test
  void finishesServerSpanForHeaderOnlyResetContentResponse() throws Exception {
    assertHeaderOnlyResponseFinishes(RESET_CONTENT_PATH, RESET_CONTENT);
  }

  @Test
  void finishesServerSpanForHeaderOnlyNotModifiedResponse() throws Exception {
    assertHeaderOnlyResponseFinishes(NOT_MODIFIED_PATH, NOT_MODIFIED);
  }

  @Test
  void finishesServerSpanForHeaderOnlyContentLengthZeroResponse() throws Exception {
    assertHeaderOnlyResponseFinishes(CONTENT_LENGTH_ZERO_PATH, OK);
  }

  @Test
  void finishesServerSpanForHeaderOnlyHeadResponse() throws Exception {
    assertHeaderOnlyResponseFinishes("HEAD", HEAD_PATH, OK);
  }

  @Test
  void finishesServerSpanForHeaderOnlyConnectResponse() throws Exception {
    assertHeaderOnlyResponseFinishes("CONNECT", CONNECT_AUTHORITY, OK, "/");
  }

  @Test
  void finishesServerSpanForHeaderOnlyWebSocketUpgrade() throws Exception {
    assertHeaderOnlyResponseFinishes(WEBSOCKET_PATH, SWITCHING_PROTOCOLS);
  }

  private void assertHeaderOnlyResponseFinishes(String path, HttpResponseStatus status)
      throws Exception {
    assertHeaderOnlyResponseFinishes("GET", path, status, path);
  }

  private void assertHeaderOnlyResponseFinishes(
      String method, String path, HttpResponseStatus status) throws Exception {
    assertHeaderOnlyResponseFinishes(method, path, status, path);
  }

  private void assertHeaderOnlyResponseFinishes(
      String method, String path, HttpResponseStatus status, String resourcePath) throws Exception {
    try (Socket socket = connect()) {
      socket.getOutputStream().write(request(method, path).getBytes(US_ASCII));
      socket.getOutputStream().flush();

      handler.awaitHeaderOnlyResponseWritten(path);
      assertResponseStatus(readHeaders(socket.getInputStream()), status);
      assertTrue(
          writer.waitForTracesMax(1, 5),
          "server span should be reported after header-only response " + status.code());
    }

    assertTraces(trace(serverSpan(method, resourcePath)));
  }

  private static String request() {
    return request(PATH);
  }

  private static String request(String path) {
    return request("GET", path);
  }

  private static String request(String method, String path) {
    String headers = method + " " + path + " HTTP/1.1\r\nHost: localhost\r\n";
    if (WEBSOCKET_PATH.equals(path)) {
      headers +=
          "Connection: Upgrade\r\n"
              + "Upgrade: websocket\r\n"
              + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
              + "Sec-WebSocket-Version: 13\r\n";
    }
    return headers + "\r\n";
  }

  private static SpanMatcher serverSpan(String path) {
    return serverSpan("GET", path);
  }

  private static SpanMatcher serverSpan(String method, String path) {
    return span()
        .root()
        .operationName(Pattern.compile("netty\\.request"))
        .resourceName(Pattern.compile(method + " " + Pattern.quote(path)))
        .type("web");
  }

  private static void assertResponseStatus(String headers, HttpResponseStatus status) {
    assertTrue(
        headers.startsWith("HTTP/1.1 " + status.code() + " "), "unexpected response: " + headers);
  }

  private void writeFirstChunk(ChannelHandlerContext responseContext) {
    responseContext.executor().execute(() -> handler.writeChunkedResponse(responseContext));
  }

  private static void writeLastChunk(ChannelHandlerContext responseContext) {
    responseContext
        .executor()
        .execute(() -> responseContext.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT));
  }

  private static void closeChannel(ChannelHandlerContext responseContext) {
    responseContext.executor().execute(() -> responseContext.channel().close());
  }

  @ChannelHandler.Sharable
  private static final class ChunkedResponseHandler
      extends SimpleChannelInboundHandler<HttpRequest> {
    private final BlockingQueue<ChannelHandlerContext> continueWrites = new LinkedBlockingQueue<>();
    private final BlockingQueue<ChannelHandlerContext> earlyHintsWrites =
        new LinkedBlockingQueue<>();
    private final BlockingQueue<ChannelHandlerContext> firstChunkWrites =
        new LinkedBlockingQueue<>();
    private final BlockingQueue<ChannelHandlerContext> closeDelimitedFullResponseWrites =
        new LinkedBlockingQueue<>();
    private final BlockingQueue<String> headerOnlyWrites = new LinkedBlockingQueue<>();
    private final BlockingQueue<ChannelHandlerContext> requestsWithoutResponse =
        new LinkedBlockingQueue<>();

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, HttpRequest request) {
      if (CONTINUE_PATH.equals(request.uri())) {
        ctx.writeAndFlush(new DefaultFullHttpResponse(HTTP_1_1, CONTINUE))
            .addListener(future -> continueWrites.offer(ctx));
      } else if (EARLY_HINTS_PATH.equals(request.uri())) {
        ctx.writeAndFlush(new DefaultFullHttpResponse(HTTP_1_1, EARLY_HINTS))
            .addListener(future -> earlyHintsWrites.offer(ctx));
      } else if (NO_CONTENT_PATH.equals(request.uri())) {
        writeHeaderOnlyResponse(ctx, request.uri(), NO_CONTENT);
      } else if (RESET_CONTENT_PATH.equals(request.uri())) {
        writeHeaderOnlyResponse(ctx, request.uri(), RESET_CONTENT);
      } else if (NOT_MODIFIED_PATH.equals(request.uri())) {
        writeHeaderOnlyResponse(ctx, request.uri(), NOT_MODIFIED);
      } else if (CONTENT_LENGTH_ZERO_PATH.equals(request.uri())) {
        writeContentLengthZeroResponse(ctx, request.uri());
      } else if (HEAD_PATH.equals(request.uri())) {
        writeHeaderOnlyResponse(ctx, request.uri(), OK);
      } else if (CONNECT_AUTHORITY.equals(request.uri())) {
        writeHeaderOnlyResponse(ctx, request.uri(), OK);
      } else if (NO_RESPONSE_PATH.equals(request.uri())) {
        requestsWithoutResponse.offer(ctx);
      } else if (CLOSE_DELIMITED_PATH.equals(request.uri())) {
        writeCloseDelimitedResponse(ctx);
      } else if (CLOSE_DELIMITED_FULL_RESPONSE_PATH.equals(request.uri())) {
        writeCloseDelimitedFullResponse(ctx);
      } else if (WEBSOCKET_PATH.equals(request.uri())) {
        writeHeaderOnlyWebSocketUpgrade(ctx, request.uri());
      } else {
        writeChunkedResponse(ctx);
      }
    }

    private void writeChunkedResponse(ChannelHandlerContext ctx) {
      DefaultHttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
      response.headers().set(TRANSFER_ENCODING, CHUNKED);
      ctx.write(response);
      ctx.writeAndFlush(new DefaultHttpContent(Unpooled.copiedBuffer("first", UTF_8)))
          .addListener(future -> firstChunkWrites.offer(ctx));
    }

    private void writeCloseDelimitedResponse(ChannelHandlerContext ctx) {
      // No Content-Length and no chunked transfer-encoding: the body is delimited by the connection
      // closing.
      DefaultHttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
      response.headers().set(CONNECTION, "close");
      ctx.write(response);
      ctx.writeAndFlush(new DefaultHttpContent(Unpooled.copiedBuffer("first", UTF_8)))
          .addListener(future -> firstChunkWrites.offer(ctx));
    }

    private void writeCloseDelimitedFullResponse(ChannelHandlerContext ctx) {
      // No Content-Length and no chunked transfer-encoding: the full response still has a
      // close-delimited body on the wire.
      DefaultFullHttpResponse response =
          new DefaultFullHttpResponse(HTTP_1_1, OK, Unpooled.copiedBuffer("first", UTF_8));
      response.headers().set(CONNECTION, "close");
      ctx.writeAndFlush(response)
          .addListener(future -> closeDelimitedFullResponseWrites.offer(ctx));
    }

    private void writeHeaderOnlyResponse(
        ChannelHandlerContext ctx, String path, HttpResponseStatus status) {
      ctx.writeAndFlush(new DefaultHttpResponse(HTTP_1_1, status))
          .addListener(future -> headerOnlyWrites.offer(path));
    }

    private void writeContentLengthZeroResponse(ChannelHandlerContext ctx, String path) {
      DefaultHttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
      response.headers().set(CONTENT_LENGTH, 0);
      ctx.writeAndFlush(response).addListener(future -> headerOnlyWrites.offer(path));
    }

    private void writeHeaderOnlyWebSocketUpgrade(ChannelHandlerContext ctx, String path) {
      DefaultHttpResponse response = new DefaultHttpResponse(HTTP_1_1, SWITCHING_PROTOCOLS);
      response.headers().set(UPGRADE, "WebSocket");
      response.headers().set(CONNECTION, "Upgrade");
      ctx.writeAndFlush(response).addListener(future -> headerOnlyWrites.offer(path));
    }

    private ChannelHandlerContext awaitFirstChunkWritten() throws InterruptedException {
      ChannelHandlerContext responseContext = firstChunkWrites.poll(5, SECONDS);
      if (responseContext == null) {
        throw new AssertionError("server did not write the first chunk");
      }
      return responseContext;
    }

    private ChannelHandlerContext await100ContinueWritten() throws InterruptedException {
      ChannelHandlerContext responseContext = continueWrites.poll(5, SECONDS);
      if (responseContext == null) {
        throw new AssertionError("server did not write 100 Continue");
      }
      return responseContext;
    }

    private ChannelHandlerContext awaitEarlyHintsWritten() throws InterruptedException {
      ChannelHandlerContext responseContext = earlyHintsWrites.poll(5, SECONDS);
      if (responseContext == null) {
        throw new AssertionError("server did not write 103 Early Hints");
      }
      return responseContext;
    }

    private ChannelHandlerContext awaitCloseDelimitedFullResponseWritten()
        throws InterruptedException {
      ChannelHandlerContext responseContext = closeDelimitedFullResponseWrites.poll(5, SECONDS);
      if (responseContext == null) {
        throw new AssertionError("server did not write the full close-delimited response");
      }
      return responseContext;
    }

    private ChannelHandlerContext awaitRequestWithoutResponse() throws InterruptedException {
      ChannelHandlerContext responseContext = requestsWithoutResponse.poll(5, SECONDS);
      if (responseContext == null) {
        throw new AssertionError("server did not receive request without response");
      }
      return responseContext;
    }

    private void awaitHeaderOnlyResponseWritten(String path) throws InterruptedException {
      String responsePath = headerOnlyWrites.poll(5, SECONDS);
      if (responsePath == null) {
        throw new AssertionError("server did not write the header-only response");
      }
      assertEquals(path, responsePath, "server wrote header-only response for unexpected path");
    }
  }
}
