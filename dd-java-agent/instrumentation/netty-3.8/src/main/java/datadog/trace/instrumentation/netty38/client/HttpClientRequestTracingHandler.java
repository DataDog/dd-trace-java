package datadog.trace.instrumentation.netty38.client;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.netty38.client.NettyHttpClientDecorator.DECORATE;
import static datadog.trace.instrumentation.netty38.client.NettyHttpClientDecorator.NETTY_CLIENT_REQUEST;
import static datadog.trace.instrumentation.netty38.client.NettyResponseInjectAdapter.SETTER;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import datadog.trace.context.TraceScope;
import datadog.trace.instrumentation.netty38.ChannelTraceContext;
import java.net.InetSocketAddress;
import lombok.extern.slf4j.Slf4j;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelDownstreamHandler;
import org.jboss.netty.handler.codec.http.HttpRequest;

@Slf4j
public class HttpClientRequestTracingHandler extends SimpleChannelDownstreamHandler {

  private final ContextStore<Channel, ChannelTraceContext> contextStore;

  public HttpClientRequestTracingHandler(
      final ContextStore<Channel, ChannelTraceContext> contextStore) {
    this.contextStore = contextStore;
  }

  @Override
  public void writeRequested(final ChannelHandlerContext ctx, final MessageEvent msg)
      throws Exception {
    if (!(msg.getMessage() instanceof HttpRequest)) {
      ctx.sendDownstream(msg);
      return;
    }

    final ChannelTraceContext channelTraceContext =
        contextStore.putIfAbsent(ctx.getChannel(), ChannelTraceContext.Factory.INSTANCE);

    TraceScope parentScope = null;
    final TraceScope.Continuation continuation = channelTraceContext.getConnectionContinuation();
    if (continuation != null) {
      parentScope = continuation.activate();
      channelTraceContext.setConnectionContinuation(null);
    }

    final HttpRequest request = (HttpRequest) msg.getMessage();

    channelTraceContext.setClientParentSpan(activeSpan());

    final AgentSpan span = startSpan(NETTY_CLIENT_REQUEST);
    span.setTag(InstrumentationTags.DD_MEASURED, true);
    try (final AgentScope scope = activateSpan(span)) {
      DECORATE.afterStart(span);
      DECORATE.onRequest(span, request);
      DECORATE.onPeerConnection(span, (InetSocketAddress) ctx.getChannel().getRemoteAddress());

      propagate().inject(span, request.headers(), SETTER);

      channelTraceContext.setClientSpan(span);

      try {
        ctx.sendDownstream(msg);
      } catch (final Throwable throwable) {
        DECORATE.onError(span, throwable);
        DECORATE.beforeFinish(span);
        span.finish();
        throw throwable;
      }
    } finally {
      if (parentScope != null) {
        parentScope.close();
      }
    }
  }
}
