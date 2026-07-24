package datadog.trace.instrumentation.netty41.server;

import static datadog.trace.instrumentation.netty41.server.BlockingResponseHandler.BEFORE_BLOCKING_HANDLER_NAME;
import static datadog.trace.instrumentation.netty41.server.BlockingResponseHandler.HANDLER_NAME;
import static io.netty.handler.codec.http.HttpMethod.GET;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static java.util.Collections.emptyMap;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.context.Context;
import datadog.trace.instrumentation.netty41.ServerRequestContext;
import datadog.trace.instrumentation.netty41.server.NettyHttpServerDecorator.NettyBlockResponseFunction;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.local.LocalChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.util.concurrent.Future;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class BlockingResponseHandlerTest {

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
          .submit(
              () -> {
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
              .submit(
                  () -> {
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
          .submit(
              () -> {
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
              .submit(
                  () -> {
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
