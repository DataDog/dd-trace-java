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
import java.util.Deque;

@ChannelHandler.Sharable
public class HttpServerRequestTracingHandler extends ChannelInboundHandlerAdapter {
  public static HttpServerRequestTracingHandler INSTANCE = new HttpServerRequestTracingHandler();
  private static final String INCOMPLETE_RESPONSE_MESSAGE =
      "Channel closed before response completed";

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
          ServerRequestContext.add(channel, context, request);

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
        final Deque<ServerRequestContext> storedContexts =
            ServerRequestContext.removeAll(ctx.channel());
        if (storedContexts != null) {
          ServerRequestContext storedContext;
          while ((storedContext = storedContexts.pollFirst()) != null) {
            if (storedContext.isResponseStarted()) {
              finishSpanOnIncompleteResponse(storedContext.tracingContext());
            } else {
              publishSpanOnChannelClose(storedContext.tracingContext());
            }
          }
        }
      } catch (final Throwable ignored) {
      }
    }
  }

  private static void finishSpanOnIncompleteResponse(final Context storedContext) {
    final AgentSpan span = AgentSpan.fromContext(storedContext);
    if (span != null) {
      DECORATE.onError(span, new IllegalStateException(INCOMPLETE_RESPONSE_MESSAGE));
      DECORATE.beforeFinish(storedContext);
      span.finish();
    }
  }

  private static void publishSpanOnChannelClose(final Context storedContext) {
    final AgentSpan span = AgentSpan.fromContext(storedContext);
    if (span != null && span.phasedFinish()) {
      // At this point we can just publish this span to avoid losing the rest of the trace.
      span.publish();
    }
  }
}
