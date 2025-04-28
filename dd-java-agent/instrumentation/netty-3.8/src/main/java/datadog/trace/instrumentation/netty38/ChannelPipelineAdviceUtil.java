package datadog.trace.instrumentation.netty38;

import datadog.trace.api.InstrumenterConfig;
import datadog.trace.bootstrap.CallDepthThreadLocalMap;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.instrumentation.netty38.client.HttpClientRequestTracingHandler;
import datadog.trace.instrumentation.netty38.client.HttpClientResponseTracingHandler;
import datadog.trace.instrumentation.netty38.client.HttpClientTracingHandler;
import datadog.trace.instrumentation.netty38.server.HttpServerRequestTracingHandler;
import datadog.trace.instrumentation.netty38.server.HttpServerResponseTracingHandler;
import datadog.trace.instrumentation.netty38.server.HttpServerTracingHandler;
import datadog.trace.instrumentation.netty38.server.MaybeBlockResponseHandler;
import datadog.trace.instrumentation.netty38.server.websocket.WebSocketServerRequestTracingHandler;
import datadog.trace.instrumentation.netty38.server.websocket.WebSocketServerResponseTracingHandler;
import datadog.trace.instrumentation.netty38.server.websocket.WebSocketServerTracingHandler;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandler;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.handler.codec.http.HttpClientCodec;
import org.jboss.netty.handler.codec.http.HttpRequestDecoder;
import org.jboss.netty.handler.codec.http.HttpRequestEncoder;
import org.jboss.netty.handler.codec.http.HttpResponseDecoder;
import org.jboss.netty.handler.codec.http.HttpResponseEncoder;
import org.jboss.netty.handler.codec.http.HttpServerCodec;
import org.jboss.netty.handler.codec.http.websocketx.WebSocket13FrameDecoder;
import org.jboss.netty.handler.codec.http.websocketx.WebSocket13FrameEncoder;
import org.jboss.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;

/**
 * When certain handlers are added to the pipeline, we want to add our corresponding tracing
 * handlers. If those handlers are later removed, we may want to remove our handlers. That is not
 * currently implemented.
 */
public class ChannelPipelineAdviceUtil {
  public static void wrapHandler(
      final ContextStore<Channel, ChannelTraceContext> contextStore,
      final ChannelPipeline pipeline,
      final ChannelHandler handler) {
    try {
      // Server pipeline handlers
      if (handler instanceof HttpServerCodec) {
        pipeline.addLast(
            HttpServerTracingHandler.class.getName(), new HttpServerTracingHandler(contextStore));
        pipeline.addLast(
            MaybeBlockResponseHandler.class.getName(), new MaybeBlockResponseHandler(contextStore));
      } else if (handler instanceof HttpRequestDecoder) {
        pipeline.addLast(
            HttpServerRequestTracingHandler.class.getName(),
            new HttpServerRequestTracingHandler(contextStore));
      } else if (handler instanceof HttpResponseEncoder) {
        pipeline.addLast(
            HttpServerResponseTracingHandler.class.getName(),
            new HttpServerResponseTracingHandler(contextStore));
        pipeline.addLast(
            MaybeBlockResponseHandler.class.getName(), new MaybeBlockResponseHandler(contextStore));
      } else if (handler instanceof WebSocketServerProtocolHandler) {
        if (InstrumenterConfig.get().isWebsocketTracingEnabled()) {
          if (pipeline.get(HttpServerTracingHandler.class) != null) {
            addHandlerAfter(
                pipeline,
                "datadog.trace.instrumentation.netty38.server.HttpServerTracingHandler",
                new WebSocketServerTracingHandler(contextStore));
          }
        }
      } else if (handler instanceof WebSocket13FrameEncoder) {
        if (InstrumenterConfig.get().isWebsocketTracingEnabled()) {
          if (pipeline.get(HttpServerRequestTracingHandler.class) != null) {
            addHandlerAfter(
                pipeline,
                "datadog.trace.instrumentation.netty38.server.HttpServerRequestTracingHandler",
                new WebSocketServerRequestTracingHandler(contextStore));
          }
        }
      } else if (handler instanceof WebSocket13FrameDecoder) {
        if (InstrumenterConfig.get().isWebsocketTracingEnabled()) {
          if (pipeline.get(HttpServerResponseTracingHandler.class) != null) {
            addHandlerAfter(
                pipeline,
                "datadog.trace.instrumentation.netty38.server.HttpServerResponseTracingHandler",
                new WebSocketServerResponseTracingHandler(contextStore));
          }
        }
      } else
      // Client pipeline handlers
      if (handler instanceof HttpClientCodec) {
        pipeline.addLast(
            HttpClientTracingHandler.class.getName(), new HttpClientTracingHandler(contextStore));
      } else if (handler instanceof HttpRequestEncoder) {
        pipeline.addLast(
            HttpClientRequestTracingHandler.class.getName(),
            new HttpClientRequestTracingHandler(contextStore));
      } else if (handler instanceof HttpResponseDecoder) {
        pipeline.addLast(
            HttpClientResponseTracingHandler.class.getName(),
            new HttpClientResponseTracingHandler(contextStore));
      }
    } finally {
      CallDepthThreadLocalMap.reset(ChannelPipeline.class);
    }
  }

  private static void addHandlerAfter(
      final ChannelPipeline pipeline, final String name, final ChannelHandler handler) {
    ChannelHandler existing = pipeline.get(handler.getClass());
    if (existing != null) {
      pipeline.remove(existing);
    }
    pipeline.addAfter(name, handler.getClass().getName(), handler);
  }
}
