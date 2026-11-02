package datadog.trace.instrumentation.netty41.client;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopSpan;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator.isNestedClientSpanSuppressed;
import static datadog.trace.instrumentation.netty41.AttributeKeys.CLIENT_PARENT_ATTRIBUTE_KEY;
import static datadog.trace.instrumentation.netty41.AttributeKeys.CONTEXT_ATTRIBUTE_KEY;
import static datadog.trace.instrumentation.netty41.client.NettyHttpClientDecorator.DECORATE;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.util.Attribute;

@ChannelHandler.Sharable
public class HttpClientResponseTracingHandler extends ChannelInboundHandlerAdapter {
  public static final HttpClientResponseTracingHandler INSTANCE =
      new HttpClientResponseTracingHandler();

  @Override
  public void channelRead(final ChannelHandlerContext ctx, final Object msg) {
    final Attribute<AgentSpan> parentAttr = ctx.channel().attr(CLIENT_PARENT_ATTRIBUTE_KEY);
    parentAttr.setIfAbsent(noopSpan());
    final AgentSpan parent = parentAttr.get();
    final Context storedContext = ctx.channel().attr(CONTEXT_ATTRIBUTE_KEY).get();
    final AgentSpan span = AgentSpan.fromContext(storedContext);
    final boolean clientSpanSuppressed = isNestedClientSpanSuppressed(storedContext, span);
    final AgentSpan callbackParent = clientSpanSuppressed ? span : parent;

    // Set parent context back to maintain the same functionality as getAndSet(parent)
    if (storedContext != null) {
      ctx.channel().attr(CONTEXT_ATTRIBUTE_KEY).set(storedContext.with(callbackParent));
    }

    if (span != null && !clientSpanSuppressed) {
      final boolean finishSpan =
          msg instanceof HttpResponse
              && (!HttpResponseStatus.SWITCHING_PROTOCOLS.equals(((HttpResponse) msg).status())
                  || "websocket"
                      .equals(((HttpResponse) msg).headers().get(HttpHeaderNames.UPGRADE)));
      if (finishSpan) {
        try (final ContextScope scope = activateSpan(span)) {
          DECORATE.onResponse(span, (HttpResponse) msg);
          DECORATE.beforeFinish(span);
          span.finish();
        }
      } else {
        if (storedContext != null) {
          ctx.channel().attr(CONTEXT_ATTRIBUTE_KEY).set(storedContext);
        }
      }
    }

    // Run the callback under the span that initiated the transport request.
    try (final ContextScope scope = activateSpan(callbackParent)) {
      ctx.fireChannelRead(msg);
    }
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
    final Attribute<AgentSpan> parentAttr = ctx.channel().attr(CLIENT_PARENT_ATTRIBUTE_KEY);
    parentAttr.setIfAbsent(noopSpan());
    final AgentSpan parent = parentAttr.get();
    final Context storedContext = ctx.channel().attr(CONTEXT_ATTRIBUTE_KEY).get();
    final AgentSpan span = AgentSpan.fromContext(storedContext);
    final boolean clientSpanSuppressed = isNestedClientSpanSuppressed(storedContext, span);
    final AgentSpan callbackParent = clientSpanSuppressed ? span : parent;

    // Set parent context back to maintain the same functionality as getAndSet(parent)
    if (storedContext != null) {
      ctx.channel().attr(CONTEXT_ATTRIBUTE_KEY).set(storedContext.with(callbackParent));
    }

    if (span != null && !clientSpanSuppressed) {
      // If an exception is passed to this point, it likely means it was unhandled and the
      // client span won't be finished with a proper response, so we should finish the span here.
      try (final ContextScope scope = activateSpan(span)) {
        DECORATE.onError(span, cause);
        DECORATE.beforeFinish(span);
        span.finish();
      }
    }
    // Run the callback under the span that initiated the transport request.
    try (final ContextScope scope = activateSpan(callbackParent)) {
      super.exceptionCaught(ctx, cause);
    }
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    final Attribute<AgentSpan> parentAttr = ctx.channel().attr(CLIENT_PARENT_ATTRIBUTE_KEY);
    parentAttr.setIfAbsent(noopSpan());
    final AgentSpan parent = parentAttr.get();
    final Context storedContext = ctx.channel().attr(CONTEXT_ATTRIBUTE_KEY).get();
    final AgentSpan span = AgentSpan.fromContext(storedContext);
    final boolean clientSpanSuppressed = isNestedClientSpanSuppressed(storedContext, span);
    final AgentSpan callbackParent = clientSpanSuppressed ? span : parent;

    // Set parent context back to maintain the same functionality  as getAndSet(parent)
    if (storedContext != null) {
      ctx.channel().attr(CONTEXT_ATTRIBUTE_KEY).set(storedContext.with(callbackParent));
    }

    if (span != null && span != parent && !clientSpanSuppressed) {
      try (final ContextScope scope = activateSpan(span)) {
        DECORATE.beforeFinish(span);
        span.finish();
      }
    }
    // Run the callback under the span that initiated the transport request.
    try (final ContextScope scope = activateSpan(callbackParent)) {
      super.channelInactive(ctx);
    }
  }
}
