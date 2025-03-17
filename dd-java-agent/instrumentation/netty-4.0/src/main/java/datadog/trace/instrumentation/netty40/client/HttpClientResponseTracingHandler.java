package datadog.trace.instrumentation.netty40.client;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.noopSpan;
import static datadog.trace.instrumentation.netty40.AttributeKeys.CLIENT_PARENT_ATTRIBUTE_KEY;
import static datadog.trace.instrumentation.netty40.AttributeKeys.SPAN_ATTRIBUTE_KEY;
import static datadog.trace.instrumentation.netty40.client.NettyHttpClientDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpResponse;
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
    final AgentSpan span = ctx.channel().attr(SPAN_ATTRIBUTE_KEY).getAndSet(parent);

    final boolean finishSpan = msg instanceof HttpResponse;

    if (span != null && finishSpan) {
      try (final AgentScope scope = activateSpan(span)) {
        DECORATE.onResponse(span, (HttpResponse) msg);
        DECORATE.beforeFinish(span);
        span.finish();
      }
    }

    // We want the callback in the scope of the parent, not the client span
    try (final AgentScope scope = activateSpan(parent)) {
      ctx.fireChannelRead(msg);
    }
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
    final Attribute<AgentSpan> parentAttr = ctx.channel().attr(CLIENT_PARENT_ATTRIBUTE_KEY);
    parentAttr.setIfAbsent(noopSpan());
    final AgentSpan parent = parentAttr.get();
    final AgentSpan span = ctx.channel().attr(SPAN_ATTRIBUTE_KEY).getAndSet(parent);
    if (span != null) {
      // If an exception is passed to this point, it likely means it was unhandled and the
      // client span won't be finished with a proper response, so we should finish the span here.
      try (final AgentScope scope = activateSpan(span)) {
        DECORATE.onError(span, cause);
        DECORATE.beforeFinish(span);
        span.finish();
      }
    }
    // We want the callback in the scope of the parent, not the client span
    try (final AgentScope scope = activateSpan(parent)) {
      super.exceptionCaught(ctx, cause);
    }
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    final Attribute<AgentSpan> parentAttr = ctx.channel().attr(CLIENT_PARENT_ATTRIBUTE_KEY);
    parentAttr.setIfAbsent(noopSpan());
    final AgentSpan parent = parentAttr.get();
    final AgentSpan span = ctx.channel().attr(SPAN_ATTRIBUTE_KEY).getAndSet(parent);
    if (span != null && span != parent) {
      try (final AgentScope scope = activateSpan(span)) {
        DECORATE.beforeFinish(span);
        span.finish();
      }
    }
    // We want the callback in the scope of the parent, not the client span
    try (final AgentScope scope = activateSpan(parent)) {
      super.channelInactive(ctx);
    }
  }
}
