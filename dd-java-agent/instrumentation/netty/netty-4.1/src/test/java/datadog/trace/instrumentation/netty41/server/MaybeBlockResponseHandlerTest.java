package datadog.trace.instrumentation.netty41.server;

import static datadog.trace.api.gateway.Events.EVENTS;
import static datadog.trace.instrumentation.netty41.AttributeKeys.CONTEXT_ATTRIBUTE_KEY;
import static datadog.trace.instrumentation.netty41.server.NettyHttpServerDecorator.DECORATE;
import static io.netty.handler.codec.http.HttpHeaderNames.UPGRADE;
import static io.netty.handler.codec.http.HttpResponseStatus.FORBIDDEN;
import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpResponseStatus.SWITCHING_PROTOCOLS;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.appsec.api.blocking.BlockingContentType;
import datadog.context.Context;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.api.function.TriConsumer;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.gateway.SubscriptionService;
import datadog.trace.bootstrap.ActiveSubsystems;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.instrumentation.netty41.ServerRequestContext;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.ReferenceCountUtil;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.tabletest.junit.TableTest;

class MaybeBlockResponseHandlerTest extends AbstractInstrumentationTest {

  private static final HttpResponseStatus EARLY_HINTS = new HttpResponseStatus(103, "Early Hints");

  private Object appSecSubscriptions;
  private boolean originalAppSecActive;

  @AfterEach
  void resetAppSec() {
    if (appSecSubscriptions != null) {
      ((SubscriptionService) appSecSubscriptions).reset();
      appSecSubscriptions = null;
      ActiveSubsystems.APPSEC_ACTIVE = originalAppSecActive;
    }
  }

  @Test
  void blocksFinalResponseUsingMirroredContextAfterInformationalResponse() {
    enableAppSecResponseBlocking();
    Context context = DECORATE.startSpan(new DefaultHttpHeaders(), Context.root());
    AgentSpan span = AgentSpan.fromContext(context);
    EmbeddedChannel channel = new EmbeddedChannel(MaybeBlockResponseHandler.INSTANCE);
    channel.attr(CONTEXT_ATTRIBUTE_KEY).set(context);
    FullHttpResponse informationalResponse = null;
    FullHttpResponse response = null;

    try {
      channel.writeOutbound(new DefaultFullHttpResponse(HTTP_1_1, EARLY_HINTS));

      informationalResponse = channel.readOutbound();
      assertNotNull(informationalResponse);
      assertEquals(EARLY_HINTS, informationalResponse.status());
      informationalResponse.release();
      informationalResponse = null;

      channel.writeOutbound(new DefaultFullHttpResponse(HTTP_1_1, OK));

      response = channel.readOutbound();
      assertNotNull(response);
      assertEquals(FORBIDDEN, response.status());
    } finally {
      ReferenceCountUtil.release(informationalResponse);
      ReferenceCountUtil.release(response);
      channel.finishAndReleaseAll();
      span.finish();
    }
  }

  @Test
  void blocksNonWebSocketSwitchingProtocolsResponse() {
    enableAppSecResponseBlocking();
    Context context = DECORATE.startSpan(new DefaultHttpHeaders(), Context.root());
    AgentSpan span = AgentSpan.fromContext(context);
    EmbeddedChannel channel = new EmbeddedChannel(MaybeBlockResponseHandler.INSTANCE);
    channel.attr(CONTEXT_ATTRIBUTE_KEY).set(context);
    FullHttpResponse response = null;

    try {
      FullHttpResponse switchingProtocols =
          new DefaultFullHttpResponse(HTTP_1_1, SWITCHING_PROTOCOLS);
      switchingProtocols.headers().set(UPGRADE, "h2c");

      channel.writeOutbound(switchingProtocols);

      response = channel.readOutbound();
      assertNotNull(response);
      assertEquals(FORBIDDEN, response.status());
    } finally {
      ReferenceCountUtil.release(response);
      channel.finishAndReleaseAll();
      span.finish();
    }
  }

  @Test
  void dropsWritesAfterBlockedContextHasBeenRemoved() {
    EmbeddedChannel channel = new EmbeddedChannel(MaybeBlockResponseHandler.INSTANCE);
    ServerRequestContext serverContext = ServerRequestContext.add(channel, Context.root(), null);
    ServerRequestContext.markResponseBlocked(channel);
    ServerRequestContext.remove(channel, serverContext);
    ByteBuf lateResponseChunk = Unpooled.buffer().writeByte(1);
    ChannelPromise promise = channel.newPromise();

    channel.pipeline().write(lateResponseChunk, promise);

    assertEquals(0, lateResponseChunk.refCnt());
    assertTrue(promise.isDone());
    assertFalse(promise.isSuccess());
    assertTrue(promise.cause() instanceof ClosedChannelException);
    assertNull(channel.readOutbound());
    channel.finishAndReleaseAll();
  }

  @Test
  void discardedVoidPromiseDoesNotTriggerErrorResponsesAfterResponseBlock() {
    AtomicInteger exceptions = new AtomicInteger();
    EmbeddedChannel channel =
        new EmbeddedChannel(
            MaybeBlockResponseHandler.INSTANCE,
            new ChannelInboundHandlerAdapter() {
              @Override
              public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                // Model Vert.x error responses, with one retry to avoid overflowing the stack.
                if (exceptions.incrementAndGet() == 1) {
                  ctx.writeAndFlush(
                      new DefaultFullHttpResponse(HTTP_1_1, INTERNAL_SERVER_ERROR),
                      ctx.voidPromise());
                }
              }
            });
    ServerRequestContext.markResponseBlocked(channel);
    ByteBuf lateResponseChunk = Unpooled.buffer().writeByte(1);

    try {
      channel.pipeline().writeAndFlush(lateResponseChunk, channel.voidPromise());

      assertEquals(0, exceptions.get());
      assertEquals(0, lateResponseChunk.refCnt());
      assertNull(channel.readOutbound());
      assertTrue(channel.isActive());
    } finally {
      channel.finishAndReleaseAll();
    }
  }

  @TableTest({
    "Scenario                  | Void promise | Write succeeds",
    "void promise, success     | true         | true          ",
    "void promise, failure     | true         | false         ",
    "ordinary promise, success | false        | true          ",
    "ordinary promise, failure | false        | false         "
  })
  void closesChannelAfterBlockingResponseWriteCompletes(
      boolean voidPromise, boolean writeSucceeds) {
    enableAppSecResponseBlocking();
    Context context = DECORATE.startSpan(new DefaultHttpHeaders(), Context.root());
    AgentSpan span = AgentSpan.fromContext(context);
    AtomicReference<ChannelPromise> pendingWrite = new AtomicReference<>();
    AtomicReference<FullHttpResponse> blockingResponse = new AtomicReference<>();
    EmbeddedChannel channel =
        new EmbeddedChannel(
            new ChannelOutboundHandlerAdapter() {
              @Override
              public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                blockingResponse.set((FullHttpResponse) msg);
                pendingWrite.set(promise);
              }
            },
            MaybeBlockResponseHandler.INSTANCE);
    channel.attr(CONTEXT_ATTRIBUTE_KEY).set(context);
    FullHttpResponse originalResponse =
        new DefaultFullHttpResponse(HTTP_1_1, OK, Unpooled.buffer().writeByte(1));
    ChannelPromise originalPromise = voidPromise ? channel.voidPromise() : channel.newPromise();

    try {
      channel.pipeline().writeAndFlush(originalResponse, originalPromise);
      channel.checkException();

      assertEquals(0, originalResponse.refCnt());
      assertNotNull(blockingResponse.get());
      assertEquals(FORBIDDEN, blockingResponse.get().status());
      assertNotNull(pendingWrite.get());
      assertFalse(pendingWrite.get().isVoid());
      assertFalse(pendingWrite.get().isDone());
      assertTrue(channel.isActive());

      if (writeSucceeds) {
        pendingWrite.get().setSuccess();
      } else {
        pendingWrite.get().setFailure(new ClosedChannelException());
      }
      channel.runPendingTasks();
      channel.checkException();

      assertFalse(channel.isActive());
      if (!voidPromise) {
        assertTrue(originalPromise.isDone());
        assertEquals(writeSucceeds, originalPromise.isSuccess());
      }
    } finally {
      ReferenceCountUtil.release(blockingResponse.get());
      if (originalResponse.refCnt() > 0) {
        originalResponse.release();
      }
      channel.finishAndReleaseAll();
      span.finish();
    }
  }

  private void enableAppSecResponseBlocking() {
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
    subscriptions.registerCallback(
        EVENTS.responseHeader(),
        new TriConsumer<RequestContext, String, String>() {
          @Override
          public void accept(RequestContext requestContext, String name, String value) {}
        });
    subscriptions.registerCallback(
        EVENTS.responseHeaderDone(),
        new Function<RequestContext, Flow<Void>>() {
          @Override
          public Flow<Void> apply(RequestContext requestContext) {
            return new Flow.ResultFlow<Void>(null) {
              @Override
              public Action getAction() {
                return new Action.RequestBlockingAction(403, BlockingContentType.AUTO);
              }
            };
          }
        });
  }
}
