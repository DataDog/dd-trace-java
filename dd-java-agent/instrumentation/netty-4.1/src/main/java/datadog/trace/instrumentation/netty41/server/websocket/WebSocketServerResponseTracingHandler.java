package datadog.trace.instrumentation.netty41.server.websocket;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.decorator.WebsocketDecorator.DECORATE;
import static datadog.trace.bootstrap.instrumentation.websocket.HandlersExtractor.MESSAGE_TYPE_BINARY;
import static datadog.trace.bootstrap.instrumentation.websocket.HandlersExtractor.MESSAGE_TYPE_TEXT;
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
public class WebSocketServerResponseTracingHandler extends ChannelOutboundHandlerAdapter {
  public static WebSocketServerResponseTracingHandler INSTANCE =
      new WebSocketServerResponseTracingHandler();

  @Override
  public void write(ChannelHandlerContext ctx, Object frame, ChannelPromise promise)
      throws Exception {

    if (frame instanceof WebSocketFrame) {
      Channel channel = ctx.channel();
      HandlerContext.Sender handlerContext = channel.attr(WEBSOCKET_HANDLER_CONTEXT).get();
      if (handlerContext != null) {

        if (frame instanceof TextWebSocketFrame) {
          // WebSocket Write Text Start
          TextWebSocketFrame textFrame = (TextWebSocketFrame) frame;
          final AgentSpan span =
              DECORATE.onSendFrameStart(
                  handlerContext, MESSAGE_TYPE_TEXT, textFrame.text().length());
          if (span == null) {
            ctx.write(frame, promise);
          } else {
            try (final AgentScope scope = activateSpan(span)) {
              ctx.write(frame, promise);

              // WebSocket Write Text End
              if (textFrame.isFinalFragment()) {
                DECORATE.onFrameEnd(handlerContext);
              }
            }
          }
          return;
        }

        if (frame instanceof BinaryWebSocketFrame) {
          // WebSocket Write Binary Start
          BinaryWebSocketFrame binaryFrame = (BinaryWebSocketFrame) frame;
          final AgentSpan span =
              DECORATE.onSendFrameStart(
                  handlerContext, MESSAGE_TYPE_BINARY, binaryFrame.content().readableBytes());
          if (span == null) {
            ctx.write(frame, promise);
          } else {
            try (final AgentScope scope = activateSpan(span)) {
              ctx.write(frame, promise);

              // WebSocket Write Binary End
              if (binaryFrame.isFinalFragment()) {
                DECORATE.onFrameEnd(handlerContext);
              }
            }
          }
          return;
        }

        if (frame instanceof CloseWebSocketFrame) {
          // WebSocket Closed by Server
          CloseWebSocketFrame closeFrame = (CloseWebSocketFrame) frame;
          int statusCode = closeFrame.statusCode();
          String reasonText = closeFrame.reasonText();
          channel.attr(WEBSOCKET_HANDLER_CONTEXT).remove();
          final AgentSpan span =
              DECORATE.onSessionCloseIssued(handlerContext, reasonText, statusCode);
          if (span == null) {
            ctx.write(frame, promise);
          } else {
            try (final AgentScope scope = activateSpan(span)) {
              ctx.write(frame, promise);
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
    ctx.write(frame, promise);
  }
}
