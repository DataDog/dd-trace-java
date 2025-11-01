package datadog.trace.instrumentation.netty38.server;

import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.spanFromContext;
import static datadog.trace.instrumentation.netty38.server.NettyHttpServerDecorator.DECORATE;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.api.gateway.Flow;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.netty38.ChannelTraceContext;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpRequest;

public class HttpServerRequestTracingHandler extends SimpleChannelUpstreamHandler {

  private final ContextStore<Channel, ChannelTraceContext> contextStore;

  public HttpServerRequestTracingHandler(
      final ContextStore<Channel, ChannelTraceContext> contextStore) {
    this.contextStore = contextStore;
  }

  @Override
  public void messageReceived(final ChannelHandlerContext ctx, final MessageEvent msg) {
    final ChannelTraceContext channelTraceContext =
        contextStore.putIfAbsent(ctx.getChannel(), ChannelTraceContext.Factory.INSTANCE);

    if (!(msg.getMessage() instanceof HttpRequest)) {
      final AgentSpan span = channelTraceContext.getServerSpan();
      if (span == null) {
        ctx.sendUpstream(msg); // superclass does not throw
      } else {
        try (final ContextScope scope = span.attach()) {
          ctx.sendUpstream(msg); // superclass does not throw
        }
      }
      return;
    }

    final HttpRequest request = (HttpRequest) msg.getMessage();
    final HttpHeaders headers = request.headers();
    final Context parentContext = DECORATE.extract(headers);
    final Context context = DECORATE.startSpan(headers, parentContext);

    channelTraceContext.reset();
    channelTraceContext.setRequestHeaders(headers);

    try (final ContextScope scope = context.attach()) {
      final AgentSpan span = spanFromContext(context);
      DECORATE.afterStart(span);
      DECORATE.onRequest(span, ctx.getChannel(), request, parentContext);

      channelTraceContext.setServerSpan(span);

      Flow.Action.RequestBlockingAction rba = span.getRequestBlockingAction();
      if (rba != null) {
        ctx.getPipeline()
            .addAfter(
                ctx.getName(),
                "blocking_handler",
                new BlockingResponseHandler(span.getRequestContext().getTraceSegment(), rba));
      }

      try {
        ctx.sendUpstream(msg);
      } catch (final Throwable throwable) {
        DECORATE.onError(span, throwable);
        DECORATE.beforeFinish(scope.context());
        span.finish(); // Finish the span manually since finishSpanOnClose was false
        throw throwable;
      }
    }
  }
}
