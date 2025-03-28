package datadog.trace.instrumentation.netty41.server.websocket;

import static datadog.trace.instrumentation.netty41.AttributeKeys.WEBSOCKET_HANDLER_CONTEXT;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.websocket.HandlerContext;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;

@ChannelHandler.Sharable
public class WebSocketProtocolHandshakeHandler extends ChannelInboundHandlerAdapter {
  public static WebSocketProtocolHandshakeHandler INSTANCE =
      new WebSocketProtocolHandshakeHandler();

  @Override
  public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
    if (evt == WebSocketServerProtocolHandler.ServerHandshakeStateEvent.HANDSHAKE_COMPLETE) {
      // WebSocket Handshake Completed
      final AgentSpan current = AgentTracer.get().activeSpan();
      if (current != null) {
        ctx.channel()
            .attr(WEBSOCKET_HANDLER_CONTEXT)
            .set(
                new HandlerContext.Sender(
                    current.getLocalRootSpan(), ctx.channel().id().asShortText()));
      }
    }
    super.userEventTriggered(ctx, evt);
  }
}
