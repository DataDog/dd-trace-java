package datadog.trace.instrumentation.netty41.server.websocket;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.decorator.WebsocketDecorator.DECORATE;
import static datadog.trace.instrumentation.netty41.AttributeKeys.WEBSOCKET_HANDLER_CONTEXT;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.websocket.HandlerContext;
import io.netty.channel.*;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;

@ChannelHandler.Sharable
public class WebSocketServerRequestTracingHandler extends ChannelInboundHandlerAdapter {
  public static WebSocketServerRequestTracingHandler INSTANCE =
      new WebSocketServerRequestTracingHandler();

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object frame) {

    if (frame instanceof WebSocketFrame) {
      Channel channel = ctx.channel();
      final HandlerContext.Sender sessionState = channel.attr(WEBSOCKET_HANDLER_CONTEXT).get();
      if (sessionState != null) {
        final HandlerContext.Receiver handlerContext =
            new HandlerContext.Receiver(
                sessionState.getHandshakeSpan(), ctx.channel().id().asShortText());

        if (frame instanceof TextWebSocketFrame) {
          // WebSocket Read Text Start
          TextWebSocketFrame textFrame = (TextWebSocketFrame) frame;

          final AgentSpan span =
              DECORATE.onReceiveFrameStart(
                  handlerContext, textFrame.text(), textFrame.isFinalFragment());
          if (span == null) {
            ctx.fireChannelRead(textFrame);
          } else {
            try (final AgentScope scope = activateSpan(span)) {
              ctx.fireChannelRead(textFrame);

              // WebSocket Read Text Start
              if (textFrame.isFinalFragment()) {
                DECORATE.onFrameEnd(handlerContext);
              }
            }
          }
          return;
        }

        if (frame instanceof BinaryWebSocketFrame) {
          // WebSocket Read Binary Start
          BinaryWebSocketFrame binaryFrame = (BinaryWebSocketFrame) frame;
          final AgentSpan span =
              DECORATE.onReceiveFrameStart(
                  handlerContext, binaryFrame.content().nioBuffer(), binaryFrame.isFinalFragment());
          if (span == null) {
            ctx.fireChannelRead(binaryFrame);
          } else {
            try (final AgentScope scope = activateSpan(span)) {
              ctx.fireChannelRead(binaryFrame);

              // WebSocket Read Binary End
              if (binaryFrame.isFinalFragment()) {
                DECORATE.onFrameEnd(handlerContext);
              }
            }
          }
          return;
        }

        if (frame instanceof CloseWebSocketFrame) {
          // WebSocket Closed by client
          CloseWebSocketFrame closeFrame = (CloseWebSocketFrame) frame;
          int statusCode = closeFrame.statusCode();
          String reasonText = closeFrame.reasonText();
          channel.attr(WEBSOCKET_HANDLER_CONTEXT).remove();
          final AgentSpan span =
              DECORATE.onSessionCloseReceived(handlerContext, reasonText, statusCode);
          if (span == null) {
            ctx.fireChannelRead(closeFrame);
          } else {
            try (final AgentScope scope = activateSpan(span)) {
              ctx.fireChannelRead(closeFrame);

              if (closeFrame.isFinalFragment()) {
                DECORATE.onFrameEnd(handlerContext);
              }
            }
          }
          return;
        }
      }
    }
    // can be other messages we do not handle like ping, pong or continuations
    ctx.fireChannelRead(frame);
  }
}
