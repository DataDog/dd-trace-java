package datadog.trace.instrumentation.netty38.server.websocket;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.instrumentation.netty38.ChannelTraceContext;
import datadog.trace.instrumentation.netty38.util.CombinedSimpleChannelHandler;
import org.jboss.netty.channel.Channel;

public class WebSocketServerTracingHandler
    extends CombinedSimpleChannelHandler<
        WebSocketServerRequestTracingHandler, WebSocketServerResponseTracingHandler> {

  public WebSocketServerTracingHandler(
      final ContextStore<Channel, ChannelTraceContext> contextStore) {
    super(
        new WebSocketServerRequestTracingHandler(contextStore),
        new WebSocketServerResponseTracingHandler(contextStore));
  }
}
