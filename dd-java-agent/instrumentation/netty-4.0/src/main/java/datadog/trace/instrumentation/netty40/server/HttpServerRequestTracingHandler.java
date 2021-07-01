package datadog.trace.instrumentation.netty40.server;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.netty40.AttributeKeys.SPAN_ATTRIBUTE_KEY;
import static datadog.trace.instrumentation.netty40.server.NettyHttpServerDecorator.DECORATE;
import static datadog.trace.instrumentation.netty40.server.NettyHttpServerDecorator.NETTY_REQUEST;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan.Context;
import datadog.trace.bootstrap.instrumentation.api.ContextVisitors;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpRequest;

@ChannelHandler.Sharable
public class HttpServerRequestTracingHandler extends ChannelInboundHandlerAdapter {
  public static HttpServerRequestTracingHandler INSTANCE = new HttpServerRequestTracingHandler();

  @Override
  public void channelRead(final ChannelHandlerContext ctx, final Object msg) {

    if (!(msg instanceof HttpRequest)) {
      final AgentSpan span = ctx.channel().attr(SPAN_ATTRIBUTE_KEY).get();
      if (span == null) {
        ctx.fireChannelRead(msg); // superclass does not throw
      } else {
        try (final AgentScope scope = activateSpan(span)) {
          scope.setAsyncPropagation(true);
          ctx.fireChannelRead(msg); // superclass does not throw
        }
      }
      return;
    }

    final HttpRequest request = (HttpRequest) msg;

    final Context.Extracted extractedContext =
        propagate().extract(request.headers(), ContextVisitors.stringValuesEntrySet());

    final AgentSpan span = startSpan(NETTY_REQUEST, extractedContext);
    span.setMeasured(true);
    try (final AgentScope scope = activateSpan(span)) {
      DECORATE.afterStart(span);
      DECORATE.onRequest(span, ctx.channel(), request, extractedContext);

      scope.setAsyncPropagation(true);

      ctx.channel().attr(SPAN_ATTRIBUTE_KEY).set(span);

      try {
        ctx.fireChannelRead(msg);
      } catch (final Throwable throwable) {
        DECORATE.onError(span, throwable);
        DECORATE.beforeFinish(span);
        span.finish(); // Finish the span manually since finishSpanOnClose was false
        throw throwable;
      }
    }
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
    final AgentSpan span = ctx.channel().attr(SPAN_ATTRIBUTE_KEY).get();
    if (span != null) {
      // If an exception is passed to this point, it likely means it was unhandled and the
      // server span won't be finished with a proper response, so we should finish the span here.
      span.setError(true);
      DECORATE.onError(span, cause);
      DECORATE.beforeFinish(span);
      span.finish();
    }
    super.exceptionCaught(ctx, cause);
  }
}
