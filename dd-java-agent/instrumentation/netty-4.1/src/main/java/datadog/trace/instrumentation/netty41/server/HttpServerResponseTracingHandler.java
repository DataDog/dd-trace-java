package datadog.trace.instrumentation.netty41.server;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.instrumentation.netty41.AttributeKeys.SPAN_ATTRIBUTE_KEY;
import static datadog.trace.instrumentation.netty41.server.NettyHttpServerDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpResponse;

@ChannelHandler.Sharable
public class HttpServerResponseTracingHandler extends ChannelOutboundHandlerAdapter {
  public static HttpServerResponseTracingHandler INSTANCE = new HttpServerResponseTracingHandler();

  @Override
  public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise prm) {
    final AgentSpan span = ctx.channel().attr(SPAN_ATTRIBUTE_KEY).get();
    if (span == null || !(msg instanceof HttpResponse)) {
      ctx.write(msg, prm);
      return;
    }

    try (final AgentScope scope = activateSpan(span)) {
      final HttpResponse response = (HttpResponse) msg;

      try {
        ctx.write(msg, prm);
      } catch (final Throwable throwable) {
        DECORATE.onError(span, throwable);
        span.setHttpStatusCode(500);
        span.finish(); // Finish the span manually since finishSpanOnClose was false
        throw throwable;
      }
      DECORATE.onResponse(span, response);
      DECORATE.beforeFinish(span);
      span.finish(); // Finish the span manually since finishSpanOnClose was false
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
