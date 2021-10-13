package datadog.trace.instrumentation.netty41.server;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.instrumentation.netty41.AttributeKeys.SPAN_ATTRIBUTE_KEY;
import static datadog.trace.instrumentation.netty41.server.NettyHttpServerDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan.Context;
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
    if (!(msg instanceof HttpRequest)) {
      final AgentSpan span = ctx.channel().attr(SPAN_ATTRIBUTE_KEY).get();
      if (span == null) {
        ctx.fireChannelRead(msg); // superclass does not throw
      } else {
        try (final AgentScope scope = activateSpan(span)) {
          /*
          Although a call to 'span.finishThreadMigration()' would be required here to 'resume' the span related
          work we can safely skip it as the subsequent call to 'ctx.fireChannelRead(msg)' would require immediately
          suspending it back.
           */
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
      DECORATE.onRequest(span, ctx.channel(), request, extractedContext);

      scope.setAsyncPropagation(true);

      ctx.channel().attr(SPAN_ATTRIBUTE_KEY).set(span);

      try {
        /*
        This handler is done with the span - suspend it before proceeding.
        The span was newly created and is stored in the channel related context such that other handlers
        may continue processing it.
        */
        span.startThreadMigration();
        ctx.fireChannelRead(msg);
        /*
        The handler chain started from 'fireChannelRead(msg)' will finish the span if successful
        */
      } catch (final Throwable throwable) {
        /*
        The handler chain failed with exception - need to finish the span here
         */
        DECORATE.onError(span, throwable);
        DECORATE.beforeFinish(span);
        // The span was 'suspended' - we need to 'resume' it before finishing
        span.finishThreadMigration();
        span.finish(); // Finish the span manually since finishSpanOnClose was false
        throw throwable;
      }
    }
  }
}
