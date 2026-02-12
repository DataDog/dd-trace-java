package datadog.trace.instrumentation.netty40.server;

import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.spanFromContext;
import static datadog.trace.instrumentation.netty40.AttributeKeys.ANALYZED_RESPONSE_KEY;
import static datadog.trace.instrumentation.netty40.AttributeKeys.BLOCKED_RESPONSE_KEY;
import static datadog.trace.instrumentation.netty40.AttributeKeys.CONTEXT_ATTRIBUTE_KEY;
import static datadog.trace.instrumentation.netty40.AttributeKeys.REQUEST_HEADERS_ATTRIBUTE_KEY;
import static datadog.trace.instrumentation.netty40.server.NettyHttpServerDecorator.DECORATE;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.api.gateway.Flow;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;

@ChannelHandler.Sharable
public class HttpServerRequestTracingHandler extends ChannelInboundHandlerAdapter {
  public static HttpServerRequestTracingHandler INSTANCE = new HttpServerRequestTracingHandler();

  @Override
  public void channelRead(final ChannelHandlerContext ctx, final Object msg) {

    Channel channel = ctx.channel();
    if (!(msg instanceof HttpRequest)) {
      final Context storedContext = channel.attr(CONTEXT_ATTRIBUTE_KEY).get();
      if (storedContext == null) {
        ctx.fireChannelRead(msg); // superclass does not throw
      } else {
        try (final ContextScope scope = storedContext.attach()) {
          ctx.fireChannelRead(msg); // superclass does not throw
        }
      }
      return;
    }

    final HttpRequest request = (HttpRequest) msg;
    final HttpHeaders headers = request.headers();
    final Context parentContext = DECORATE.extract(headers);
    final Context context = DECORATE.startSpan(headers, parentContext);

    try (final ContextScope ignored = context.attach()) {
      final AgentSpan span = spanFromContext(context);
      DECORATE.afterStart(span);
      DECORATE.onRequest(span, channel, request, parentContext);

      channel.attr(ANALYZED_RESPONSE_KEY).set(null);
      channel.attr(BLOCKED_RESPONSE_KEY).set(null);

      channel.attr(CONTEXT_ATTRIBUTE_KEY).set(context);
      channel.attr(REQUEST_HEADERS_ATTRIBUTE_KEY).set(request.headers());

      Flow.Action.RequestBlockingAction rba = span.getRequestBlockingAction();
      if (rba != null) {
        ctx.pipeline()
            .addAfter(
                ctx.name(),
                "blocking_handler",
                new BlockingResponseHandler(span.getRequestContext().getTraceSegment(), rba));
      }

      try {
        ctx.fireChannelRead(msg);
      } catch (final Throwable throwable) {
        DECORATE.onError(span, throwable);
        DECORATE.beforeFinish(ignored.context());
        span.finish(); // Finish the span manually since finishSpanOnClose was false
        ctx.channel().attr(CONTEXT_ATTRIBUTE_KEY).remove();
        throw throwable;
      }
    }
  }

  @Override
  public void channelInactive(ChannelHandlerContext ctx) throws Exception {
    try {
      super.channelInactive(ctx);
    } finally {
      try {
        final Context storedContext = ctx.channel().attr(CONTEXT_ATTRIBUTE_KEY).getAndRemove();
        final AgentSpan span = spanFromContext(storedContext);
        if (span != null && span.phasedFinish()) {
          // at this point we can just publish this span to avoid loosing the rest of the trace
          span.publish();
        }
      } catch (final Throwable ignored) {
      }
    }
  }
}
