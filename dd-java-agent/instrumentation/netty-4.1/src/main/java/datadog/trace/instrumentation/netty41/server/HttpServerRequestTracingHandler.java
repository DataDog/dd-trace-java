package datadog.trace.instrumentation.netty41.server;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.instrumentation.netty41.AttributeKeys.ANALYZED_RESPONSE_KEY;
import static datadog.trace.instrumentation.netty41.AttributeKeys.BLOCKED_RESPONSE_KEY;
import static datadog.trace.instrumentation.netty41.AttributeKeys.REQUEST_HEADERS_ATTRIBUTE_KEY;
import static datadog.trace.instrumentation.netty41.AttributeKeys.SPAN_ATTRIBUTE_KEY;
import static datadog.trace.instrumentation.netty41.server.NettyHttpServerDecorator.DECORATE;

import datadog.trace.api.Config;
import datadog.trace.api.GlobalTracer;
import datadog.trace.api.gateway.Flow;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan.Context;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;

import java.util.Enumeration;
import java.util.List;
import java.util.Map;

@ChannelHandler.Sharable
public class HttpServerRequestTracingHandler extends ChannelInboundHandlerAdapter {
  public static HttpServerRequestTracingHandler INSTANCE = new HttpServerRequestTracingHandler();

  @Override
  public void channelRead(final ChannelHandlerContext ctx, final Object msg) {
    Channel channel = ctx.channel();
    if (!(msg instanceof HttpRequest)) {
      final AgentSpan span = channel.attr(SPAN_ATTRIBUTE_KEY).get();
      if (span == null) {
        ctx.fireChannelRead(msg); // superclass does not throw
      } else {
        try (final AgentScope scope = activateSpan(span)) {
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

    addTag(span,headers);

    try (final AgentScope scope = activateSpan(span)) {
      DECORATE.afterStart(span);
      DECORATE.onRequest(span, channel, request, extractedContext);

      scope.setAsyncPropagation(true);

      channel.attr(ANALYZED_RESPONSE_KEY).set(null);
      channel.attr(BLOCKED_RESPONSE_KEY).set(null);

      channel.attr(SPAN_ATTRIBUTE_KEY).set(span);
      channel.attr(REQUEST_HEADERS_ATTRIBUTE_KEY).set(request.headers());

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
        DECORATE.beforeFinish(span);
        span.finish(); // Finish the span manually since finishSpanOnClose was false
        throw throwable;
      }
    }
  }

  private void addTag(AgentSpan span,HttpHeaders headers){
    StringBuffer requestHeader = new StringBuffer("");
    boolean tracerHeader = Config.get().isTracerHeaderEnabled();
    if (tracerHeader) {
      int count = 0;
      for (Map.Entry<String, String> entry : headers.entries()) {
        if (count==0){
          requestHeader.append("{");
        }else{
          requestHeader.append(",");
        }
        requestHeader.append("\n\"").append(entry.getKey()).append("\":").append("\"").append(entry.getValue().replace("\"","")).append("\"");
        count ++;
      }
      if (count>0){
        requestHeader.append("}");
      }
    }
    span.setTag("request_header",requestHeader.toString());
  }
}
