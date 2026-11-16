package datadog.trace.instrumentation.netty41.server;

import static datadog.trace.instrumentation.netty41.server.BlockingResponseHandler.BEFORE_BLOCKING_HANDLER_NAME;
import static datadog.trace.instrumentation.netty41.server.BlockingResponseHandler.HANDLER_NAME;
import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static java.util.Collections.emptyMap;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.context.Context;
import datadog.trace.instrumentation.netty41.ServerRequestContext;
import datadog.trace.instrumentation.netty41.server.NettyHttpServerDecorator.NettyBlockResponseFunction;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.local.LocalChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.util.concurrent.Future;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class BlockingResponseHandlerTest {

  @ParameterizedTest
  @MethodSource("discardedHttpWritesArguments")
  void discardedHttpWritesDoNotFireExceptions(HttpContent message) {
    EmbeddedChannel channel =
        new EmbeddedChannel(BlockingResponseHandler.IgnoreAllWritesHandler.INSTANCE);
    try {
      channel.pipeline().writeAndFlush(message, channel.voidPromise());

      channel.checkException();
      assertEquals(0, message.refCnt());
      assertNull(channel.readOutbound());
      assertTrue(channel.isActive());
    } finally {
      channel.finishAndReleaseAll();
    }
  }

  @ParameterizedTest
  @MethodSource("discardedHttpWritesArguments")
  void discardedHttpWritesCompletePromiseAndNotifyListener(HttpContent message) {
    EmbeddedChannel channel =
        new EmbeddedChannel(BlockingResponseHandler.IgnoreAllWritesHandler.INSTANCE);
    AtomicReference<ChannelFuture> notifiedPromise = new AtomicReference<>();
    try {
      ChannelFuture future = channel.pipeline().writeAndFlush(message);
      future.addListener((ChannelFutureListener) notifiedPromise::set);

      assertTrue(future.isSuccess());
      assertSame(future, notifiedPromise.get());
      channel.checkException();
      assertEquals(0, message.refCnt());
      assertNull(channel.readOutbound());
      assertTrue(channel.isActive());
    } finally {
      channel.finishAndReleaseAll();
    }
  }

  static Stream<HttpContent> discardedHttpWritesArguments() {
    return Stream.of(
        new DefaultFullHttpResponse(HTTP_1_1, OK, Unpooled.buffer().writeByte(1)),
        new DefaultLastHttpContent(Unpooled.buffer().writeByte(1)));
  }

  @Test
  void discardedCloseFrameCompletesPromiseAndNotifiesCloseListener() {
    EmbeddedChannel channel =
        new EmbeddedChannel(BlockingResponseHandler.IgnoreAllWritesHandler.INSTANCE);
    CloseWebSocketFrame frame = new CloseWebSocketFrame();
    ChannelPromise promise = channel.newPromise();
    promise.addListener(ChannelFutureListener.CLOSE);

    try {
      channel.pipeline().writeAndFlush(frame, promise);

      assertTrue(promise.isDone());
      assertInstanceOf(ClosedChannelException.class, promise.cause());
      assertEquals(0, frame.refCnt());
      assertNull(channel.readOutbound());
      assertFalse(channel.isActive());
    } finally {
      channel.finishAndReleaseAll();
    }
  }

  @Test
  void removesResponseFunctionHandlersWhenResponseTracingIsUnavailable() {
    EmbeddedChannel channel = new EmbeddedChannel();
    ServerRequestContext serverContext = ServerRequestContext.add(channel, Context.root(), null);
    BlockingResponseHandler blockingHandler =
        new BlockingResponseHandler(
            null, 403, BlockingContentType.NONE, emptyMap(), null, serverContext);
    channel
        .pipeline()
        .addLast(BEFORE_BLOCKING_HANDLER_NAME, new ChannelInboundHandlerAdapter())
        .addLast(HANDLER_NAME, blockingHandler);
    FullHttpRequest request = new DefaultFullHttpRequest(HTTP_1_1, GET, "/");

    assertTrue(channel.writeInbound(request));

    assertFalse(ServerRequestContext.isRequestBlocked(channel));
    assertNull(channel.pipeline().get(BEFORE_BLOCKING_HANDLER_NAME));
    assertNull(channel.pipeline().get(HANDLER_NAME));
    assertSame(request, channel.readInbound());
    request.release();
    channel.finishAndReleaseAll();
  }

  @Test
  void doesNotInstallBlockingHandlerWhenAuxiliaryHandlerNameIsUnavailable() {
    ChannelInboundHandlerAdapter existingBeforeBlockingHandler = new ChannelInboundHandlerAdapter();
    EmbeddedChannel channel = new EmbeddedChannel(HttpServerRequestTracingHandler.INSTANCE);
    channel.pipeline().addLast(BEFORE_BLOCKING_HANDLER_NAME, existingBeforeBlockingHandler);
    FullHttpRequest request = new DefaultFullHttpRequest(HTTP_1_1, GET, "/");
    NettyBlockResponseFunction responseFunction =
        new NettyBlockResponseFunction(channel.pipeline(), request, null);

    assertFalse(
        responseFunction.tryCommitBlockingResponse(
            null, 403, BlockingContentType.NONE, emptyMap(), null));

    assertSame(existingBeforeBlockingHandler, channel.pipeline().get(BEFORE_BLOCKING_HANDLER_NAME));
    assertNull(channel.pipeline().get(HANDLER_NAME));
    request.release();
    channel.finishAndReleaseAll();
  }

  @Test
  void commitsOffEventLoopAfterOriginalRequestIsReleased() throws Exception {
    DefaultEventLoopGroup eventLoopGroup = new DefaultEventLoopGroup(1);
    LocalChannel channel = new LocalChannel();
    FullHttpRequest request = new DefaultFullHttpRequest(HTTP_1_1, GET, "/");
    CountDownLatch eventLoopTaskStarted = new CountDownLatch(1);
    CountDownLatch releaseEventLoop = new CountDownLatch(1);

    try {
      eventLoopGroup.register(channel).sync();
      AtomicReference<ServerRequestContext> serverContext = new AtomicReference<>();
      channel
          .eventLoop()
          .submit(() -> {
            channel
                .pipeline()
                .addLast(HttpServerRequestTracingHandler.INSTANCE)
                .addLast(HttpServerResponseTracingHandler.INSTANCE);
            ServerRequestContext.add(channel, Context.root(), null);
            serverContext.set(ServerRequestContext.add(channel, Context.root(), null));
          })
          .sync();

      Future<?> blockingTask =
          channel
              .eventLoop()
              .submit(() -> {
                eventLoopTaskStarted.countDown();
                if (!releaseEventLoop.await(5, SECONDS)) {
                  throw new AssertionError("event loop was not released");
                }
                return null;
              });
      assertTrue(eventLoopTaskStarted.await(5, SECONDS), "event loop task did not start");

      NettyBlockResponseFunction responseFunction =
          new NettyBlockResponseFunction(channel.pipeline(), request, serverContext.get());
      assertTrue(
          responseFunction.tryCommitBlockingResponse(
              null, 403, BlockingContentType.NONE, emptyMap(), null));
      request.release();

      assertNull(channel.pipeline().get(BEFORE_BLOCKING_HANDLER_NAME));
      assertNull(channel.pipeline().get(HANDLER_NAME));

      releaseEventLoop.countDown();
      blockingTask.sync();
      channel.eventLoop().submit(() -> {}).sync();

      assertTrue(ServerRequestContext.isRequestBlocked(channel));
      assertNotNull(channel.pipeline().get(BEFORE_BLOCKING_HANDLER_NAME));
      assertNotNull(channel.pipeline().get(HANDLER_NAME));
    } finally {
      releaseEventLoop.countDown();
      channel.close().sync();
      eventLoopGroup.shutdownGracefully().sync();
      if (request.refCnt() > 0) {
        request.release();
      }
    }
  }

  @Test
  void doesNotBlockChannelWhenScheduledRequestContextCompletesFirst() throws Exception {
    DefaultEventLoopGroup eventLoopGroup = new DefaultEventLoopGroup(1);
    LocalChannel channel = new LocalChannel();
    FullHttpRequest request = new DefaultFullHttpRequest(HTTP_1_1, GET, "/");
    CountDownLatch eventLoopTaskStarted = new CountDownLatch(1);
    CountDownLatch releaseEventLoop = new CountDownLatch(1);

    try {
      eventLoopGroup.register(channel).sync();
      AtomicReference<ServerRequestContext> serverContext = new AtomicReference<>();
      channel
          .eventLoop()
          .submit(() -> {
            channel
                .pipeline()
                .addLast(HttpServerRequestTracingHandler.INSTANCE)
                .addLast(HttpServerResponseTracingHandler.INSTANCE);
            serverContext.set(ServerRequestContext.add(channel, Context.root(), null));
          })
          .sync();

      Future<?> blockingTask =
          channel
              .eventLoop()
              .submit(() -> {
                eventLoopTaskStarted.countDown();
                if (!releaseEventLoop.await(5, SECONDS)) {
                  throw new AssertionError("event loop was not released");
                }
                return null;
              });
      assertTrue(eventLoopTaskStarted.await(5, SECONDS), "event loop task did not start");

      Future<?> completionTask =
          channel
              .eventLoop()
              .submit(() -> ServerRequestContext.remove(channel, serverContext.get()));
      NettyBlockResponseFunction responseFunction =
          new NettyBlockResponseFunction(channel.pipeline(), request, serverContext.get());
      assertTrue(
          responseFunction.tryCommitBlockingResponse(
              null, 403, BlockingContentType.NONE, emptyMap(), null));
      request.release();

      releaseEventLoop.countDown();
      blockingTask.sync();
      completionTask.sync();
      channel.eventLoop().submit(() -> {}).sync();

      assertFalse(ServerRequestContext.isRequestBlocked(channel));
      assertNull(channel.pipeline().get(BEFORE_BLOCKING_HANDLER_NAME));
      assertNull(channel.pipeline().get(HANDLER_NAME));
    } finally {
      releaseEventLoop.countDown();
      channel.close().sync();
      eventLoopGroup.shutdownGracefully().sync();
      if (request.refCnt() > 0) {
        request.release();
      }
    }
  }
}
