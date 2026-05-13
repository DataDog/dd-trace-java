package datadog.trace.instrumentation.netty41.server;

import static datadog.trace.agent.test.assertions.Matchers.any;
import static datadog.trace.agent.test.assertions.Matchers.is;
import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TagsMatcher.defaultTags;
import static datadog.trace.agent.test.assertions.TagsMatcher.tag;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
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
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * Tests that the Netty HTTP server instrumentation correctly handles chunked (streaming) responses.
 *
 * <p>The existing Netty41ServerTest uses HttpObjectAggregator which converts all responses to
 * FullHttpResponse — so the chunked path (HttpResponse + HttpContent* + LastHttpContent) is never
 * exercised. This test fills that gap.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class NettyChunkedResponseTest extends AbstractInstrumentationTest {

  private static final long CHUNK_DELAY_MS = 200;
  private static final int CHUNK_COUNT = 5;
  private static final Pattern NETTY_REQUEST = Pattern.compile("netty\\.request");
  private static final Pattern GET_CHUNKED = Pattern.compile("GET /chunked");
  private static final Pattern GET_FULL = Pattern.compile("GET /full");

  private EventLoopGroup eventLoopGroup;
  private int port;

  @BeforeAll
  void startServer() throws Exception {
    eventLoopGroup = new NioEventLoopGroup();
    ServerBootstrap bootstrap =
        new ServerBootstrap()
            .group(eventLoopGroup)
            .channel(NioServerSocketChannel.class)
            .childHandler(
                new ChannelInitializer<Channel>() {
                  @Override
                  protected void initChannel(Channel ch) {
                    ch.pipeline().addLast(new HttpServerCodec());
                    ch.pipeline().addLast(new HttpObjectAggregator(65536));
                    ch.pipeline().addLast(new ChunkedTestHandler());
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

  /**
   * Verifies that the span for a chunked HTTP response covers the full streaming duration, not just
   * the time to send headers. Without the fix in HttpServerResponseTracingHandler, the span would
   * finish when HttpResponse (headers) is written (~0ms), ignoring the time spent writing
   * HttpContent chunks and LastHttpContent.
   */
  @Test
  void chunkedResponseSpanIncludesFullStreamDuration() throws Exception {
    String body = doGet("/chunked");
    assertEquals("chunk0chunk1chunk2chunk3chunk4", body);

    long expectedMinDurationMs = CHUNK_DELAY_MS * CHUNK_COUNT;

    assertTraces(
        trace(
            span()
                .root()
                .operationName(NETTY_REQUEST)
                .resourceName(GET_CHUNKED)
                .durationLongerThan(Duration.ofMillis(expectedMinDurationMs))
                .type("web")
                .tags(
                    defaultTags(),
                    tag("http.status_code", is(200)),
                    tag("http.method", any()),
                    tag("http.url", any()),
                    tag("http.hostname", any()),
                    tag("http.useragent", any()),
                    tag("component", any()),
                    tag("span.kind", any()),
                    tag("peer.port", any()),
                    tag("peer.ipv4", any()))));
  }

  /**
   * Regression test: a non-chunked FullHttpResponse must still finish the span immediately. This
   * ensures the instanceof ordering fix (FullHttpResponse checked before HttpResponse and
   * LastHttpContent) does not break the standard single-message response path.
   */
  @Test
  void fullResponseStillFinishesSpanImmediately() throws Exception {
    String body = doGet("/full");
    assertEquals("full-response", body);

    assertTraces(
        trace(
            span()
                .root()
                .operationName(NETTY_REQUEST)
                .resourceName(GET_FULL)
                .durationShorterThan(Duration.ofMillis(500))
                .type("web")
                .tags(
                    defaultTags(),
                    tag("http.status_code", is(200)),
                    tag("http.method", any()),
                    tag("http.url", any()),
                    tag("http.hostname", any()),
                    tag("http.useragent", any()),
                    tag("component", any()),
                    tag("span.kind", any()),
                    tag("peer.port", any()),
                    tag("peer.ipv4", any()))));
  }

  /**
   * Verifies that two sequential chunked requests each produce a correctly-timed span. This
   * exercises the STREAMING_CONTEXT_KEY lifecycle across multiple requests on the same connection:
   * each request must set and clear the key independently. Note: HttpURLConnection sends requests
   * sequentially (no pipelining), so this does not reproduce the concurrent race condition — it
   * validates that the streaming context bookkeeping works correctly for back-to-back requests.
   */
  @Test
  void keepAliveSequentialChunkedRequestsGetCorrectSpans() throws Exception {
    URL url = new URL("http://localhost:" + port + "/chunked");

    HttpURLConnection conn1 = (HttpURLConnection) url.openConnection();
    conn1.setRequestProperty("Connection", "keep-alive");
    String body1 = readResponse(conn1);
    assertEquals("chunk0chunk1chunk2chunk3chunk4", body1);
    conn1.disconnect();

    HttpURLConnection conn2 = (HttpURLConnection) url.openConnection();
    conn2.setRequestProperty("Connection", "keep-alive");
    String body2 = readResponse(conn2);
    assertEquals("chunk0chunk1chunk2chunk3chunk4", body2);
    conn2.disconnect();

    long expectedMinDurationMs = CHUNK_DELAY_MS * CHUNK_COUNT;

    assertTraces(
        trace(
            span()
                .root()
                .operationName(NETTY_REQUEST)
                .resourceName(GET_CHUNKED)
                .durationLongerThan(Duration.ofMillis(expectedMinDurationMs))
                .type("web")
                .tags(
                    defaultTags(),
                    tag("http.status_code", is(200)),
                    tag("http.method", any()),
                    tag("http.url", any()),
                    tag("http.hostname", any()),
                    tag("http.useragent", any()),
                    tag("component", any()),
                    tag("span.kind", any()),
                    tag("peer.port", any()),
                    tag("peer.ipv4", any()))),
        trace(
            span()
                .root()
                .operationName(NETTY_REQUEST)
                .resourceName(GET_CHUNKED)
                .durationLongerThan(Duration.ofMillis(expectedMinDurationMs))
                .type("web")
                .tags(
                    defaultTags(),
                    tag("http.status_code", is(200)),
                    tag("http.method", any()),
                    tag("http.url", any()),
                    tag("http.hostname", any()),
                    tag("http.useragent", any()),
                    tag("component", any()),
                    tag("span.kind", any()),
                    tag("peer.port", any()),
                    tag("peer.ipv4", any()))));
  }

  /**
   * Verifies that a streaming span is properly finished (not leaked) when the client disconnects
   * mid-stream. Without the channelInactive fix in HttpServerRequestTracingHandler, the span stored
   * in STREAMING_CONTEXT_KEY would never be finished if LastHttpContent is never written because
   * the connection dropped.
   */
  @Test
  void connectionDropDuringChunkedResponseFinishesSpan() throws Exception {
    try (Socket socket = new Socket("localhost", port)) {
      socket.setSoTimeout(5000);
      socket
          .getOutputStream()
          .write("GET /slow-chunked HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes());
      socket.getOutputStream().flush();
      // Read until we get at least the first chunk — synchronization point before closing
      byte[] buf = new byte[512];
      socket.getInputStream().read(buf);
    }
    // Socket closed — channelInactive should fire and finish the streaming span

    Thread.sleep(1500);

    // The span must be finished (not leaked) and marked as error since the channel
    // closed before the response completed. Duration should be much shorter than
    // the full 10s streaming time since we disconnected early.
    assertTraces(
        trace(
            span()
                .root()
                .operationName(NETTY_REQUEST)
                .resourceName(Pattern.compile("GET /slow-chunked"))
                .type("web")
                .error()
                .durationShorterThan(Duration.ofMillis(5000))
                .tags(
                    defaultTags(),
                    tag("http.status_code", is(200)),
                    tag("http.method", any()),
                    tag("http.url", any()),
                    tag("http.hostname", any()),
                    tag("http.useragent", any()),
                    tag("component", any()),
                    tag("span.kind", any()),
                    tag("peer.port", any()),
                    tag("peer.ipv4", any()),
                    tag("error.type", any()),
                    tag("error.message", any()),
                    tag("error.stack", any()))));
  }

  private String doGet(String path) throws Exception {
    URL url = new URL("http://localhost:" + port + path);
    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
    try {
      return readResponse(conn);
    } finally {
      conn.disconnect();
    }
  }

  private String readResponse(HttpURLConnection conn) throws Exception {
    StringBuilder sb = new StringBuilder();
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        sb.append(line);
      }
    }
    return sb.toString();
  }

  static class ChunkedTestHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
      String uri = request.uri();
      if ("/chunked".equals(uri)) {
        handleChunked(ctx);
      } else if ("/slow-chunked".equals(uri)) {
        handleSlowChunked(ctx);
      } else if ("/full".equals(uri)) {
        handleFull(ctx);
      } else {
        DefaultFullHttpResponse resp =
            new DefaultFullHttpResponse(
                HTTP_1_1,
                HttpResponseStatus.NOT_FOUND,
                Unpooled.copiedBuffer("not found", StandardCharsets.UTF_8));
        resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, resp.content().readableBytes());
        ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
      }
    }

    private void handleChunked(ChannelHandlerContext ctx) {
      DefaultHttpResponse headers = new DefaultHttpResponse(HTTP_1_1, OK);
      headers.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
      ctx.writeAndFlush(headers);

      ctx.executor()
          .execute(
              () -> {
                try {
                  for (int i = 0; i < CHUNK_COUNT; i++) {
                    Thread.sleep(CHUNK_DELAY_MS);
                    byte[] data = ("chunk" + i).getBytes(StandardCharsets.UTF_8);
                    ctx.writeAndFlush(new DefaultHttpContent(Unpooled.wrappedBuffer(data)));
                  }
                  ctx.writeAndFlush(new DefaultLastHttpContent());
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  ctx.close();
                }
              });
    }

    private void handleSlowChunked(ChannelHandlerContext ctx) {
      DefaultHttpResponse headers = new DefaultHttpResponse(HTTP_1_1, OK);
      headers.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
      ctx.writeAndFlush(headers);

      // Long streaming — 20 chunks × 500ms = 10s. The test will close the client socket
      // after the first chunk, triggering channelInactive before LastHttpContent is sent.
      ctx.executor()
          .execute(
              () -> {
                try {
                  for (int i = 0; i < 20; i++) {
                    if (!ctx.channel().isActive()) {
                      return;
                    }
                    Thread.sleep(500);
                    byte[] data = ("slow" + i).getBytes(StandardCharsets.UTF_8);
                    ctx.writeAndFlush(new DefaultHttpContent(Unpooled.wrappedBuffer(data)));
                  }
                  ctx.writeAndFlush(new DefaultLastHttpContent());
                } catch (Exception e) {
                  // Channel closed — expected
                }
              });
    }

    private void handleFull(ChannelHandlerContext ctx) {
      byte[] body = "full-response".getBytes(StandardCharsets.UTF_8);
      DefaultFullHttpResponse resp =
          new DefaultFullHttpResponse(HTTP_1_1, OK, Unpooled.wrappedBuffer(body));
      resp.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
      ctx.writeAndFlush(resp);
    }
  }
}
