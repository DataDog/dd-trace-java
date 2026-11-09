package datadog.trace.instrumentation.netty41.server;

import static io.netty.handler.codec.http.HttpHeaderNames.TRANSFER_ENCODING;
import static io.netty.handler.codec.http.HttpHeaderValues.CHUNKED;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.DDSpan;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.LastHttpContent;
import java.net.Socket;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class NettyClosedChannelClientAbortSpanTest extends NettyHttpServerTestSupport {

  private final BlockingQueue<DelayedWrite> delayedWrites = new LinkedBlockingQueue<>();

  @Override
  protected void configurePipeline(Channel channel) {
    DelayedWriteHandler delayedWriteHandler = new DelayedWriteHandler();
    channel.pipeline().addLast(delayedWriteHandler);
    channel.pipeline().addLast(new HttpServerCodec());
    channel
        .pipeline()
        .addLast(
            new SimpleChannelInboundHandler<HttpRequest>() {
              @Override
              protected void channelRead0(ChannelHandlerContext ctx, HttpRequest request) {
                DefaultHttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
                response.headers().set(TRANSFER_ENCODING, CHUNKED);
                ctx.write(response);
                ctx.writeAndFlush(new DefaultHttpContent(ctx.alloc().buffer(1).writeByte('a')));

                // Hold the encoded terminal write after the tracing handler attaches its listener.
                delayedWriteHandler.delayWrites = true;
                ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
              }
            });
  }

  @Test
  void closedChannelFromCancelledResponseDoesNotMarkServerSpanError() throws Exception {
    DelayedWrite delayedWrite = null;
    try {
      try (Socket socket = connect()) {
        socket
            .getOutputStream()
            .write("GET /closed-channel HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(US_ASCII));
        socket.getOutputStream().flush();
        assertTrue(readHeaders(socket.getInputStream()).startsWith("HTTP/1.1 200 "));

        delayedWrite = delayedWrites.poll(5, SECONDS);
        assertNotNull(delayedWrite, "server did not attempt the terminal response write");
        assertFalse(delayedWrite.promise.isDone());
        assertTrue(writer.isEmpty(), "span finished before the terminal response write");
      }

      assertTrue(
          delayedWrite.context.channel().closeFuture().await(5, SECONDS),
          "server did not observe the client disconnect");
      delayedWrite.resume();
      assertTrue(delayedWrite.promise.await(5, SECONDS), "terminal response write did not finish");
      // Recent Netty versions produce StacklessClosedChannelException; older ones use its base
      // type.
      Throwable failure = delayedWrite.promise.cause();
      assertInstanceOf(ClosedChannelException.class, failure);

      writer.waitForTraces(1);
      assertEquals(1, writer.firstTrace().size());
      DDSpan span = writer.firstTrace().get(0);
      assertEquals("netty.request", span.getOperationName().toString());
      assertEquals(200, span.getTag(Tags.HTTP_STATUS));
      assertEquals(
          0,
          span.getError(),
          () -> "client abort must not count as a trace error: " + failure.getClass().getName());
    } finally {
      if (delayedWrite != null && !delayedWrite.promise.isDone()) {
        delayedWrite.resume();
      }
    }
  }

  private final class DelayedWriteHandler extends ChannelOutboundHandlerAdapter {
    private boolean delayWrites;

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
      if (delayWrites) {
        delayedWrites.add(new DelayedWrite(ctx, msg, promise));
      } else {
        ctx.write(msg, promise);
      }
    }
  }

  private static final class DelayedWrite {
    private final ChannelHandlerContext context;
    private final Object message;
    private final ChannelPromise promise;

    private DelayedWrite(ChannelHandlerContext context, Object message, ChannelPromise promise) {
      this.context = context;
      this.message = message;
      this.promise = promise;
    }

    private void resume() throws Exception {
      // Let Netty fail the actual write to the closed channel and release the encoded buffer.
      context.executor().submit(() -> context.writeAndFlush(message, promise)).sync();
    }
  }
}
