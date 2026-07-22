package datadog.trace.instrumentation.netty41.server;

import static datadog.trace.instrumentation.netty41.AttributeKeys.CONTEXT_ATTRIBUTE_KEY;
import static datadog.trace.instrumentation.netty41.AttributeKeys.PARENT_CONTEXT_ATTRIBUTE_KEY;
import static datadog.trace.instrumentation.netty41.server.NettyHttpServerDecorator.DECORATE;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.api.gateway.Flow;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.instrumentation.netty41.ServerRequestContext;
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
    if (!ServerRequestContext.canTrackRequest(channel)) {
      channel.attr(PARENT_CONTEXT_ATTRIBUTE_KEY).remove();
      ctx.fireChannelRead(msg);
      return;
    }

    final HttpHeaders headers = request.headers();
    final Context storedParentContext = channel.attr(PARENT_CONTEXT_ATTRIBUTE_KEY).getAndRemove();
    final Context parentContext =
        storedParentContext != null ? storedParentContext : DECORATE.extract(headers);
    final Context context = DECORATE.startSpan(headers, parentContext);

    try (final ContextScope ignored = context.attach()) {
      final AgentSpan span = AgentSpan.fromContext(context);
      DECORATE.afterStart(span);
      DECORATE.onRequest(span, channel, request, parentContext);

      final ServerRequestContext serverContext =
          ServerRequestContext.add(channel, context, headers.get("accept"));

      Flow.Action.RequestBlockingAction rba = span.getRequestBlockingAction();
      if (rba != null) {
        ctx.pipeline()
            .addAfter(
                ctx.name(),
                "blocking_handler",
                new BlockingResponseHandler(
                    span.getRequestContext().getTraceSegment(), rba, serverContext));
      }

      try {
        ctx.fireChannelRead(msg);
        /*
        The handler chain started from 'fireChannelRead(msg)' will finish the span if successful
        */
      } catch (final Throwable throwable) {
        /*
        The handler chain failed with exception - need to finish the span here
         */
        DECORATE.onError(span, throwable);
        DECORATE.beforeFinish(ignored.context());
        span.finish(); // Finish the span manually since finishSpanOnClose was false
        ServerRequestContext.remove(ctx.channel(), serverContext);
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
        ServerRequestContext.closeAll(ctx.channel());
      } catch (final Throwable ignored) {
      }
    }
  }
}
