package datadog.trace.instrumentation.netty40.server;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.instrumentation.netty40.AttributeKeys.CHANNEL_ID;
import static datadog.trace.instrumentation.netty40.AttributeKeys.SPAN_ATTRIBUTE_KEY;
import static datadog.trace.instrumentation.netty40.AttributeKeys.WEBSOCKET_SENDER_HANDLER_CONTEXT;
import static datadog.trace.instrumentation.netty40.server.NettyHttpServerDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.websocket.HandlerContext;
import datadog.trace.util.RandomUtils;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;

@ChannelHandler.Sharable
public class HttpServerResponseTracingHandler extends ChannelOutboundHandlerAdapter {
  public static HttpServerResponseTracingHandler INSTANCE = new HttpServerResponseTracingHandler();
  private static final String UPGRADE_HEADER = "upgrade";

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
        ctx.channel().attr(SPAN_ATTRIBUTE_KEY).remove();
        throw throwable;
      }
      final boolean isWebsocketUpgrade =
          response.getStatus() == HttpResponseStatus.SWITCHING_PROTOCOLS
              && "websocket".equals(response.headers().get(UPGRADE_HEADER));
      if (isWebsocketUpgrade) {
        String channelId =
            ctx.channel()
                .attr(CHANNEL_ID)
                .setIfAbsent(RandomUtils.randomUUID().toString().substring(0, 8));
        ctx.channel()
            .attr(WEBSOCKET_SENDER_HANDLER_CONTEXT)
            .set(new HandlerContext.Sender(span, channelId));
      }
      if (response.getStatus() != HttpResponseStatus.CONTINUE
          && (response.getStatus() != HttpResponseStatus.SWITCHING_PROTOCOLS
              || isWebsocketUpgrade)) {
        DECORATE.onResponse(span, response);
        DECORATE.beforeFinish(span);
        ctx.channel().attr(SPAN_ATTRIBUTE_KEY).remove();
        span.finish(); // Finish the span manually since finishSpanOnClose was false
      }
    }
  }
}
