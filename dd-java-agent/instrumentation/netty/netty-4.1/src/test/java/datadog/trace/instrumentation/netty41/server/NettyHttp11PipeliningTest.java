package datadog.trace.instrumentation.netty41.server;

import static datadog.trace.agent.test.assertions.SpanMatcher.span;
import static datadog.trace.agent.test.assertions.TraceAssertions.SORT_BY_START_TIME;
import static datadog.trace.agent.test.assertions.TraceMatcher.trace;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.test.assertions.TraceMatcher;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class NettyHttp11PipeliningTest extends NettyHttpServerTestSupport {

  private static final String FIRST_PATH = "/pipelined/first";
  private static final String SECOND_PATH = "/pipelined/second";
  private static final String THIRD_PATH = "/pipelined/third";

  private final PipeliningHandler handler = new PipeliningHandler(3);

  @Override
  protected void configurePipeline(Channel ch) {
    ch.pipeline().addLast(new HttpServerCodec());
    ch.pipeline().addLast(new HttpObjectAggregator(65536));
    ch.pipeline().addLast(handler);
  }

  @Test
  void createsServerSpanForEachPipelinedRequest() throws Exception {
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

  private static TraceMatcher serverTrace(String path) {
    return trace(
        span()
            .root()
            .operationName(Pattern.compile("netty\\.request"))
            .resourceName(Pattern.compile("GET " + Pattern.quote(path)))
            .type("web"));
  }

  private static final class PipeliningHandler
      extends SimpleChannelInboundHandler<FullHttpRequest> {
    private final CountDownLatch receivedRequests;
    private final List<String> paths = new ArrayList<>();
    private volatile ChannelHandlerContext context;

    private PipeliningHandler(int expectedRequests) {
      receivedRequests = new CountDownLatch(expectedRequests);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
      context = ctx;
      synchronized (paths) {
        paths.add(request.uri());
      }
      receivedRequests.countDown();
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
  }
}
