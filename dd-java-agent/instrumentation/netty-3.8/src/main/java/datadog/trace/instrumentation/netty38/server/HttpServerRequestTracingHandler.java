package datadog.trace.instrumentation.netty38.server;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.netty38.server.NettyHttpServerDecorator.DECORATE;
import static datadog.trace.instrumentation.netty38.server.NettyHttpServerDecorator.NETTY_REQUEST;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan.Context;
import datadog.trace.bootstrap.instrumentation.api.ContextVisitors;
import datadog.trace.instrumentation.netty38.ChannelTraceContext;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.handler.codec.http.HttpRequest;

public class HttpServerRequestTracingHandler extends SimpleChannelUpstreamHandler {

  private final ContextStore<Channel, ChannelTraceContext> contextStore;

  public HttpServerRequestTracingHandler(
      final ContextStore<Channel, ChannelTraceContext> contextStore) {
    this.contextStore = contextStore;
  }

  @Override
  public void messageReceived(final ChannelHandlerContext ctx, final MessageEvent msg)
      throws Exception {
    final ChannelTraceContext channelTraceContext =
        contextStore.putIfAbsent(ctx.getChannel(), ChannelTraceContext.Factory.INSTANCE);

    if (!(msg.getMessage() instanceof HttpRequest)) {
      final AgentSpan span = channelTraceContext.getServerSpan();
      if (span == null) {
        ctx.sendUpstream(msg); // superclass does not throw
      } else {
        try (final AgentScope scope = activateSpan(span)) {
          scope.setAsyncPropagation(true);
          ctx.sendUpstream(msg); // superclass does not throw
        }
      }
      return;
    }

    final HttpRequest request = (HttpRequest) msg.getMessage();

    final Context.Extracted context =
        propagate().extract(request.headers(), ContextVisitors.stringValuesEntrySet());

    final AgentSpan span = startSpan(NETTY_REQUEST, context);
    span.setMeasured(true);
    try (final AgentScope scope = activateSpan(span)) {
      DECORATE.afterStart(span);
      DECORATE.onRequest(span, ctx.getChannel(), request, context);

      scope.setAsyncPropagation(true);

      channelTraceContext.setServerSpan(span);

      try {
        ctx.sendUpstream(msg);
      } catch (final Throwable throwable) {
        DECORATE.onError(span, throwable);
        DECORATE.beforeFinish(span);
        span.finish(); // Finish the span manually since finishSpanOnClose was false
        throw throwable;
      }
    }
  }

  @Override
  public void exceptionCaught(ChannelHandlerContext ctx, ExceptionEvent e) throws Exception {
    final ChannelTraceContext channelTraceContext =
        contextStore.putIfAbsent(ctx.getChannel(), ChannelTraceContext.Factory.INSTANCE);

    final AgentSpan span = channelTraceContext.getServerSpan();
    if (span != null) {
      // If an exception is passed to this point, it likely means it was unhandled and the
      // server span won't be finished with a proper response, so we should finish the span here.
      span.setError(true);
      DECORATE.onError(span, e.getCause());
      DECORATE.beforeFinish(span);
      span.finish();
    }
    super.exceptionCaught(ctx, e);
  }
}
