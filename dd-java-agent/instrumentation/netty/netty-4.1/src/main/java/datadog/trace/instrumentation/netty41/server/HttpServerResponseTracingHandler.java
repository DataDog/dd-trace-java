package datadog.trace.instrumentation.netty41.server;

import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.spanFromContext;
import static datadog.trace.instrumentation.netty41.AttributeKeys.CONTEXT_ATTRIBUTE_KEY;
import static datadog.trace.instrumentation.netty41.AttributeKeys.STREAMING_CONTEXT_KEY;
import static datadog.trace.instrumentation.netty41.AttributeKeys.WEBSOCKET_SENDER_HANDLER_CONTEXT;
import static datadog.trace.instrumentation.netty41.server.NettyHttpServerDecorator.DECORATE;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.websocket.HandlerContext;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.LastHttpContent;

@ChannelHandler.Sharable
public class HttpServerResponseTracingHandler extends ChannelOutboundHandlerAdapter {
  public static HttpServerResponseTracingHandler INSTANCE = new HttpServerResponseTracingHandler();

  @Override
  public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise prm) {
    final Context storedContext = ctx.channel().attr(CONTEXT_ATTRIBUTE_KEY).get();
    final AgentSpan span = spanFromContext(storedContext);

    // FullHttpResponse must be checked BEFORE LastHttpContent and HttpResponse,
    // because FullHttpResponse extends both LastHttpContent and HttpResponse.
    if (msg instanceof FullHttpResponse) {
      handleFullHttpResponse(ctx, storedContext, span, (FullHttpResponse) msg, prm);
      return;
    }

    // Handle HttpResponse (headers only — start of chunked/streaming response).
    // Must be checked BEFORE LastHttpContent/HttpContent.
    if (msg instanceof HttpResponse) {
      handleHttpResponse(ctx, storedContext, span, (HttpResponse) msg, prm);
      return;
    }

    // Handle LastHttpContent (end of chunked/streaming response).
    // Must be checked BEFORE HttpContent (LastHttpContent extends HttpContent).
    // IMPORTANT: Use STREAMING_CONTEXT_KEY to avoid keep-alive race condition where
    // channelRead for the next request may overwrite CONTEXT_ATTRIBUTE_KEY before
    // this LastHttpContent write task runs on the EventLoop.
    if (msg instanceof LastHttpContent) {
      Context streamingContext = ctx.channel().attr(STREAMING_CONTEXT_KEY).getAndRemove();
      Context contextForLastContent = streamingContext != null ? streamingContext : storedContext;
      AgentSpan spanForLastContent =
          streamingContext != null ? spanFromContext(streamingContext) : span;
      handleLastHttpContent(
          ctx, contextForLastContent, spanForLastContent, (LastHttpContent) msg, prm);
      return;
    }

    // Intermediate HttpContent chunks — pass through without touching the span.
    ctx.write(msg, prm);
  }

  /** Complete response in a single message (non-streaming). Finish span immediately. */
  private void handleFullHttpResponse(
      final ChannelHandlerContext ctx,
      final Context storedContext,
      final AgentSpan span,
      final FullHttpResponse response,
      final ChannelPromise prm) {

    if (span == null) {
      ctx.write(response, prm);
      return;
    }

    try (final ContextScope scope = storedContext.attach()) {
      try {
        ctx.write(response, prm);
      } catch (final Throwable throwable) {
        DECORATE.onError(span, throwable);
        span.setHttpStatusCode(500);
        span.finish();
        ctx.channel().attr(CONTEXT_ATTRIBUTE_KEY).remove();
        throw throwable;
      }

      final boolean isWebsocketUpgrade =
          response.status() == HttpResponseStatus.SWITCHING_PROTOCOLS
              && "websocket".equals(response.headers().get(HttpHeaderNames.UPGRADE));

      if (isWebsocketUpgrade) {
        ctx.channel()
            .attr(WEBSOCKET_SENDER_HANDLER_CONTEXT)
            .set(new HandlerContext.Sender(span, ctx.channel().id().asShortText()));
      }

      if (response.status() != HttpResponseStatus.CONTINUE
          && (response.status() != HttpResponseStatus.SWITCHING_PROTOCOLS || isWebsocketUpgrade)) {
        DECORATE.onResponse(span, response);
        DECORATE.beforeFinish(scope.context());
        span.finish();
        ctx.channel().attr(CONTEXT_ATTRIBUTE_KEY).remove();
      }
    }
  }

  /**
   * Chunked response headers — record status but do NOT finish the span yet. The span will be
   * finished when the corresponding LastHttpContent is written. Context is saved to
   * STREAMING_CONTEXT_KEY so that a keep-alive channelRead for the next request cannot overwrite it
   * before LastHttpContent arrives.
   */
  private void handleHttpResponse(
      final ChannelHandlerContext ctx,
      final Context storedContext,
      final AgentSpan span,
      final HttpResponse response,
      final ChannelPromise prm) {

    if (span == null) {
      ctx.write(response, prm);
      return;
    }

    try (final ContextScope scope = storedContext.attach()) {
      try {
        ctx.write(response, prm);
      } catch (final Throwable throwable) {
        DECORATE.onError(span, throwable);
        span.setHttpStatusCode(500);
        span.finish();
        ctx.channel().attr(CONTEXT_ATTRIBUTE_KEY).remove();
        throw throwable;
      }

      final boolean isWebsocketUpgrade =
          response.status() == HttpResponseStatus.SWITCHING_PROTOCOLS
              && "websocket".equals(response.headers().get(HttpHeaderNames.UPGRADE));

      if (isWebsocketUpgrade) {
        ctx.channel()
            .attr(WEBSOCKET_SENDER_HANDLER_CONTEXT)
            .set(new HandlerContext.Sender(span, ctx.channel().id().asShortText()));
      }

      if (response.status() != HttpResponseStatus.CONTINUE
          && (response.status() != HttpResponseStatus.SWITCHING_PROTOCOLS || isWebsocketUpgrade)) {
        DECORATE.onResponse(span, response);
        ctx.channel().attr(STREAMING_CONTEXT_KEY).set(storedContext);
        // Span finish is deferred to handleLastHttpContent.
      }
    }
  }

  /** End of chunked/streaming response — finish the span now that the full duration is known. */
  private void handleLastHttpContent(
      final ChannelHandlerContext ctx,
      final Context storedContext,
      final AgentSpan span,
      final LastHttpContent msg,
      final ChannelPromise prm) {

    if (span == null) {
      ctx.write(msg, prm);
      return;
    }

    try (final ContextScope scope = storedContext.attach()) {
      try {
        ctx.write(msg, prm);
      } catch (final Throwable throwable) {
        DECORATE.onError(span, throwable);
        span.setHttpStatusCode(500);
        span.finish();
        ctx.channel().attr(CONTEXT_ATTRIBUTE_KEY).remove();
        throw throwable;
      }

      DECORATE.beforeFinish(scope.context());
      span.finish();
      // Only remove CONTEXT_ATTRIBUTE_KEY if it still holds our context.
      // Under keep-alive a new request's channelRead may have already replaced it.
      // All channel ops run on the same EventLoop thread so this check is race-free.
      if (ctx.channel().attr(CONTEXT_ATTRIBUTE_KEY).get() == storedContext) {
        ctx.channel().attr(CONTEXT_ATTRIBUTE_KEY).remove();
      }
    }
  }
}
