package datadog.trace.instrumentation.netty38.server;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.instrumentation.netty38.server.NettyHttpServerDecorator.DECORATE;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.websocket.HandlerContext;
import datadog.trace.instrumentation.netty38.ChannelTraceContext;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelDownstreamHandler;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;

public class HttpServerResponseTracingHandler extends SimpleChannelDownstreamHandler {

  private final ContextStore<Channel, ChannelTraceContext> contextStore;
  private static final String UPGRADE_HEADER = "upgrade";

  public HttpServerResponseTracingHandler(
      final ContextStore<Channel, ChannelTraceContext> contextStore) {
    this.contextStore = contextStore;
  }

  @Override
  public void writeRequested(final ChannelHandlerContext ctx, final MessageEvent msg) {
    final ChannelTraceContext channelTraceContext =
        contextStore.putIfAbsent(ctx.getChannel(), ChannelTraceContext.Factory.INSTANCE);

    final AgentSpan span = channelTraceContext.getServerSpan();
    if (span == null || !(msg.getMessage() instanceof HttpResponse)) {
      ctx.sendDownstream(msg);
      return;
    }

    try (final AgentScope scope = activateSpan(span)) {
      final HttpResponse response = (HttpResponse) msg.getMessage();

      try {
        ctx.sendDownstream(msg);
      } catch (final Throwable throwable) {
        DECORATE.onError(span, throwable);
        span.setHttpStatusCode(500);
        span.finish(); // Finish the span manually since finishSpanOnClose was false
        throw throwable;
      }
      final boolean isWebsocketUpgrade =
          response.getStatus() == HttpResponseStatus.SWITCHING_PROTOCOLS
              && "websocket".equals(response.headers().get(UPGRADE_HEADER));
      if (isWebsocketUpgrade) {
        String channelId = ctx.getChannel().getId().toString();
        channelTraceContext.setSenderHandlerContext(new HandlerContext.Sender(span, channelId));
      }
      if (response.getStatus() != HttpResponseStatus.CONTINUE
          && (response.getStatus() != HttpResponseStatus.SWITCHING_PROTOCOLS
              || isWebsocketUpgrade)) {
        DECORATE.onResponse(span, response);
        DECORATE.beforeFinish(span);
        span.finish(); // Finish the span manually since finishSpanOnClose was false
      }
    }
  }
}
