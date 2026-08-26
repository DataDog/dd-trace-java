package datadog.trace.instrumentation.netty41.server;

import static io.netty.handler.codec.http.HttpHeaderNames.TRANSFER_ENCODING;
import static io.netty.handler.codec.http.HttpHeaderValues.CHUNKED;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.DDSpan;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.LastHttpContent;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

public class NettyNativeClientAbortSpanTest extends AbstractInstrumentationTest {

  private static final String PATH = "/native-broken-pipe";
  private static final String NATIVE_IO_EXCEPTION =
      "io.netty.channel.unix.Errors$NativeIoException";
  private static final String WRITEV_ADDRESSES_FAILURE_PREFIX = "writevAddresses(..) failed";
  private static final String WRITEV_SYSCALL_FAILURE_PREFIX = "syscall:writev(..) failed";
  private static final String BROKEN_PIPE_MESSAGE_SUFFIX = ": Broken pipe";
  private static final String CONNECTION_RESET_MESSAGE_SUFFIX = ": Connection reset by peer";

  @Test
  void nativeBrokenPipeFromCancelledResponseDoesNotMarkServerSpanError() throws Exception {
    NativeTransport transport = NativeTransport.current();
    assumeTrue(transport.available, transport.unavailableReason);

    NativeBrokenPipeHandler handler = new NativeBrokenPipeHandler();
    EventLoopGroup boss = transport.newEventLoopGroup(1);
    EventLoopGroup worker = transport.newEventLoopGroup(1);
    Channel server = null;
    try {
      server =
          new ServerBootstrap()
              .group(boss, worker)
              .channel(transport.serverSocketChannelClass)
              .childOption(
                  ChannelOption.WRITE_BUFFER_WATER_MARK,
                  new WriteBufferWaterMark(32 * 1024, 64 * 1024))
              .childHandler(
                  new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                      ch.pipeline().addLast(new HttpServerCodec());
                      ch.pipeline().addLast(handler);
                    }
                  })
              .bind("127.0.0.1", 0)
              .sync()
              .channel();

      int port = ((InetSocketAddress) server.localAddress()).getPort();
      try (Socket socket = new Socket("127.0.0.1", port)) {
        socket.setReceiveBufferSize(1024);
        socket.getOutputStream().write(request().getBytes(US_ASCII));
        socket.getOutputStream().flush();

        InputStream response = socket.getInputStream();
        assertTrue(response.read() >= 0, "server did not write any response bytes");
      }

      Throwable failure = handler.awaitFailure();
      assertEquals(NATIVE_IO_EXCEPTION, failure.getClass().getName());
      assertTrue(
          isExpectedClientAbortMessage(failure.getMessage()),
          () -> "unexpected native write failure message: " + failure.getMessage());

      writer.waitForTraces(1);
      DDSpan span = writer.firstTrace().get(0);
      assertFalse(span.isError(), "client abort should result in a non-error span");
      assertEquals(200, span.getTag(Tags.HTTP_STATUS));
      assertEquals(NATIVE_IO_EXCEPTION, span.getTag(DDTags.ERROR_TYPE));
      assertEquals(failure.getMessage(), span.getTag(DDTags.ERROR_MSG));
      assertNull(span.getTag(DDTags.ERROR_STACK));
    } finally {
      if (server != null) {
        server.close().syncUninterruptibly();
      }
      boss.shutdownGracefully().syncUninterruptibly();
      worker.shutdownGracefully().syncUninterruptibly();
    }
  }

  private static String request() {
    return "GET " + PATH + " HTTP/1.1\r\nHost: localhost\r\n\r\n";
  }

  private static boolean isExpectedClientAbortMessage(String message) {
    return message != null
        && (message.startsWith(WRITEV_ADDRESSES_FAILURE_PREFIX)
            || message.startsWith(WRITEV_SYSCALL_FAILURE_PREFIX))
        && (message.endsWith(BROKEN_PIPE_MESSAGE_SUFFIX)
            || message.endsWith(CONNECTION_RESET_MESSAGE_SUFFIX));
  }

  @ChannelHandler.Sharable
  private static final class NativeBrokenPipeHandler
      extends SimpleChannelInboundHandler<HttpRequest> {
    private final AtomicBoolean failureRecorded = new AtomicBoolean();
    private final BlockingQueue<Throwable> failures = new LinkedBlockingQueue<>();

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, HttpRequest request) {
      if (!PATH.equals(request.uri())) {
        ctx.close();
        return;
      }

      DefaultHttpResponse response = new DefaultHttpResponse(HTTP_1_1, OK);
      response.headers().set(TRANSFER_ENCODING, CHUNKED);
      ctx.write(response);
      ctx.writeAndFlush(new DefaultHttpContent(ctx.alloc().buffer(1).writeByte(1)))
          .addListener(future -> writeCancelledResponseTail(ctx));
    }

    private void writeCancelledResponseTail(ChannelHandlerContext ctx) {
      for (int i = 0; i < 512; i++) {
        ByteBuf content = ctx.alloc().directBuffer(16 * 1024);
        content.writeZero(content.writableBytes());
        ctx.write(new DefaultHttpContent(content));
      }
      ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
          .addListener(
              future -> {
                if (future.isSuccess()) {
                  failures.offer(
                      new AssertionError("cancelled response tail write unexpectedly succeeded"));
                } else if (failureRecorded.compareAndSet(false, true)) {
                  failures.offer(future.cause());
                }
              });
    }

    private Throwable awaitFailure() throws InterruptedException, TimeoutException {
      Throwable failure = failures.poll(5, SECONDS);
      if (failure == null) {
        throw new TimeoutException("server did not observe a failed native response write");
      }
      return failure;
    }
  }

  private static final class NativeTransport {
    private final boolean available;
    private final String unavailableReason;
    private final Constructor<? extends EventLoopGroup> eventLoopGroupConstructor;
    private final Class<? extends ServerChannel> serverSocketChannelClass;

    private NativeTransport(String unavailableReason) {
      this.available = false;
      this.unavailableReason = unavailableReason;
      this.eventLoopGroupConstructor = null;
      this.serverSocketChannelClass = null;
    }

    private NativeTransport(
        Constructor<? extends EventLoopGroup> eventLoopGroupConstructor,
        Class<? extends ServerChannel> serverSocketChannelClass) {
      this.available = true;
      this.unavailableReason = null;
      this.eventLoopGroupConstructor = eventLoopGroupConstructor;
      this.serverSocketChannelClass = serverSocketChannelClass;
    }

    private static NativeTransport current() {
      String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
      if (osName.contains("mac")) {
        return load(
            "kqueue",
            "io.netty.channel.kqueue.KQueue",
            "io.netty.channel.kqueue.KQueueEventLoopGroup",
            "io.netty.channel.kqueue.KQueueServerSocketChannel");
      } else if (osName.contains("linux")) {
        return load(
            "epoll",
            "io.netty.channel.epoll.Epoll",
            "io.netty.channel.epoll.EpollEventLoopGroup",
            "io.netty.channel.epoll.EpollServerSocketChannel");
      }
      return new NativeTransport("Netty native transport is not supported on " + osName);
    }

    private static NativeTransport load(
        String name, String availabilityClass, String eventLoopGroupClass, String channelClass) {
      try {
        Class<?> availability = Class.forName(availabilityClass);
        Method isAvailable = availability.getMethod("isAvailable");
        if (!Boolean.TRUE.equals(isAvailable.invoke(null))) {
          return new NativeTransport(name + " is not available");
        }
        return new NativeTransport(
            Class.forName(eventLoopGroupClass)
                .asSubclass(EventLoopGroup.class)
                .getConstructor(int.class),
            Class.forName(channelClass).asSubclass(ServerChannel.class));
      } catch (Throwable error) {
        return new NativeTransport(name + " could not be loaded: " + error);
      }
    }

    private EventLoopGroup newEventLoopGroup(int threads) throws Exception {
      assertNotNull(eventLoopGroupConstructor);
      return eventLoopGroupConstructor.newInstance(threads);
    }
  }
}
