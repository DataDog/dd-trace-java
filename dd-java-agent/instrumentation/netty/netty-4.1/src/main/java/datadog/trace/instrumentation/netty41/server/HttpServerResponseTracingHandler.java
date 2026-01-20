package datadog.trace.instrumentation.netty41.server;

import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.spanFromContext;
import static datadog.trace.instrumentation.netty41.AttributeKeys.CONTEXT_ATTRIBUTE_KEY;
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
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;

@ChannelHandler.Sharable
public class HttpServerResponseTracingHandler extends ChannelOutboundHandlerAdapter {
  public static HttpServerResponseTracingHandler INSTANCE = new HttpServerResponseTracingHandler();

  @Override
  public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise prm) {
    final Context storedContext = ctx.channel().attr(CONTEXT_ATTRIBUTE_KEY).get();
    final AgentSpan span = spanFromContext(storedContext);

    if (span == null
        || !(msg instanceof HttpResponse || msg instanceof Http2HeadersFrame)) {
      ctx.write(msg, prm);
      return;
    }

    try (final ContextScope scope = storedContext.attach()) {
      if (msg instanceof Http2HeadersFrame) {
        final Http2HeadersFrame headersFrame = (Http2HeadersFrame) msg;
        final Http2Headers headers = headersFrame.headers();
        try {
          ctx.write(msg, prm);
        } catch (final Throwable throwable) {
          DECORATE.onError(span, throwable);
          span.setHttpStatusCode(500);
          span.finish();
          ctx.channel().attr(CONTEXT_ATTRIBUTE_KEY).remove();
          throw throwable;
        }

        final CharSequence status = headers.status();
        if (status != null) {
          try {
            DECORATE.onResponseStatus(span, HttpResponseStatus.parseLine(status).code());
          } catch (IllegalArgumentException ignored) {
            // ignore unparsable status and finish without setting http.status_code
          }
        }
        DECORATE.beforeFinish(scope.context());
        span.finish();
        ctx.channel().attr(CONTEXT_ATTRIBUTE_KEY).remove();
        return;
      }

      final HttpResponse response = (HttpResponse) msg;

      try {
        ctx.write(msg, prm);
      } catch (final Throwable throwable) {
        DECORATE.onError(span, throwable);
        span.setHttpStatusCode(500);
        span.finish(); // Finish the span manually since finishSpanOnClose was false
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
        span.finish(); // Finish the span manually since finishSpanOnClose was false
        ctx.channel().attr(CONTEXT_ATTRIBUTE_KEY).remove();
      }
    }
  }
}
