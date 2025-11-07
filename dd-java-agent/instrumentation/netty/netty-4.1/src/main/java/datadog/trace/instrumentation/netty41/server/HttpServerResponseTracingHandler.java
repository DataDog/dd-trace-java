package datadog.trace.instrumentation.netty41.server;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.instrumentation.netty41.AttributeKeys.SPAN_ATTRIBUTE_KEY;
import static datadog.trace.instrumentation.netty41.AttributeKeys.WEBSOCKET_SENDER_HANDLER_CONTEXT;
import static datadog.trace.instrumentation.netty41.server.NettyHttpServerDecorator.DECORATE;

import datadog.trace.api.Config;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.websocket.HandlerContext;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;

import java.util.Map;

@ChannelHandler.Sharable
public class HttpServerResponseTracingHandler extends ChannelOutboundHandlerAdapter {
  public static HttpServerResponseTracingHandler INSTANCE = new HttpServerResponseTracingHandler();

  @Override
  public void write(final ChannelHandlerContext ctx, final Object msg, final ChannelPromise prm) {
    final AgentSpan span = ctx.channel().attr(SPAN_ATTRIBUTE_KEY).get();

    if (span == null || !(msg instanceof HttpResponse)) {
      ctx.write(msg, prm);
      return;
    }

    try (final AgentScope scope = activateSpan(span)) {
      final HttpResponse response = (HttpResponse) msg;
      span.setTag("ext_trace_id", span.getTraceId().toString());
      addTag(span, response.headers());
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
        DECORATE.beforeFinish(span);
        span.finish(); // Finish the span manually since finishSpanOnClose was false
        ctx.channel().attr(SPAN_ATTRIBUTE_KEY).remove();
      }
    }
  }

  private void addTag(AgentSpan span, HttpHeaders headers) {
    StringBuffer responseHeader = new StringBuffer("");
    boolean tracerHeader = Config.get().isTracerHeaderEnabled();
    if (tracerHeader) {
      int count = 0;
      for (Map.Entry<String, String> entry : headers.entries()) {
        if (count == 0) {
          responseHeader.append("{");
        } else {
          responseHeader.append(",\n");
        }
        responseHeader
            .append("\"")
            .append(entry.getKey())
            .append("\":")
            .append("\"")
            .append(entry.getValue().replace("\"", ""))
            .append("\"");
        count++;
      }
      if (count > 0) {
        responseHeader.append("}");
      }
    }
    span.setTag("response_header", responseHeader.toString());
  }
}
