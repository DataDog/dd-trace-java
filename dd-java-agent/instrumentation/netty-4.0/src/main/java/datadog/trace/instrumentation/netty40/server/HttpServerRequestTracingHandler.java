package datadog.trace.instrumentation.netty40.server;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.instrumentation.netty40.AttributeKeys.ANALYZED_RESPONSE_KEY;
import static datadog.trace.instrumentation.netty40.AttributeKeys.BLOCKED_RESPONSE_KEY;
import static datadog.trace.instrumentation.netty40.AttributeKeys.REQUEST_HEADERS_ATTRIBUTE_KEY;
import static datadog.trace.instrumentation.netty40.AttributeKeys.SPAN_ATTRIBUTE_KEY;
import static datadog.trace.instrumentation.netty40.server.NettyHttpServerDecorator.DECORATE;

import datadog.trace.api.gateway.Flow;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan.Context;
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
      final AgentSpan span = channel.attr(SPAN_ATTRIBUTE_KEY).get();
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
    final HttpHeaders headers = request.headers();
    final Context.Extracted extractedContext = DECORATE.extract(headers);
    final AgentSpan span = DECORATE.startSpan(headers, extractedContext);

    try (final AgentScope scope = activateSpan(span)) {
      DECORATE.afterStart(span);
      DECORATE.onRequest(span, channel, request, extractedContext);

      scope.setAsyncPropagation(true);

      channel.attr(ANALYZED_RESPONSE_KEY).set(null);
      channel.attr(BLOCKED_RESPONSE_KEY).set(null);

      channel.attr(SPAN_ATTRIBUTE_KEY).set(span);
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
        DECORATE.beforeFinish(span);
        span.finish(); // Finish the span manually since finishSpanOnClose was false
        throw throwable;
      }
    }
  }
}
