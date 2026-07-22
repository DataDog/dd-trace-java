package datadog.trace.instrumentation.netty41.server;

import static datadog.trace.api.gateway.Events.EVENTS;
import static datadog.trace.instrumentation.netty41.AttributeKeys.CONTEXT_ATTRIBUTE_KEY;
import static datadog.trace.instrumentation.netty41.server.NettyHttpServerDecorator.DECORATE;
import static io.netty.handler.codec.http.HttpHeaderNames.UPGRADE;
import static io.netty.handler.codec.http.HttpResponseStatus.FORBIDDEN;
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
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.ReferenceCountUtil;
import java.nio.channels.ClosedChannelException;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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
    ServerRequestContext serverContext =
        ServerRequestContext.add(channel, Context.root(), new DefaultHttpHeaders());
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
