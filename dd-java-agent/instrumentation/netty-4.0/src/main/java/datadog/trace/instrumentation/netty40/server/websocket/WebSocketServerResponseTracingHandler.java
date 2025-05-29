package datadog.trace.instrumentation.netty40.server.websocket;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.decorator.WebsocketDecorator.DECORATE;
import static datadog.trace.bootstrap.instrumentation.websocket.HandlersExtractor.MESSAGE_TYPE_BINARY;
import static datadog.trace.bootstrap.instrumentation.websocket.HandlersExtractor.MESSAGE_TYPE_TEXT;
import static datadog.trace.instrumentation.netty40.AttributeKeys.WEBSOCKET_SENDER_HANDLER_CONTEXT;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.websocket.HandlerContext;
import io.netty.channel.*;
import io.netty.handler.codec.http.websocketx.*;

@ChannelHandler.Sharable
public class WebSocketServerResponseTracingHandler extends ChannelOutboundHandlerAdapter {
  public static WebSocketServerResponseTracingHandler INSTANCE =
      new WebSocketServerResponseTracingHandler();

  @Override
  public void write(ChannelHandlerContext ctx, Object frame, ChannelPromise promise)
      throws Exception {

    if (frame instanceof WebSocketFrame) {
      Channel channel = ctx.channel();
      HandlerContext.Sender handlerContext = channel.attr(WEBSOCKET_SENDER_HANDLER_CONTEXT).get();
      if (handlerContext != null) {

        if (frame instanceof TextWebSocketFrame) {
          // WebSocket Write Text Start
          TextWebSocketFrame textFrame = (TextWebSocketFrame) frame;
          final AgentSpan span =
              DECORATE.onSendFrameStart(
                  handlerContext, MESSAGE_TYPE_TEXT, textFrame.text().length());
          try (final AgentScope scope = activateSpan(span)) {
            ctx.write(frame, promise);
          } finally {
            // WebSocket Write Text End
            if (textFrame.isFinalFragment()) {
              DECORATE.onFrameEnd(handlerContext);
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
          try (final AgentScope scope = activateSpan(span)) {
            ctx.write(frame, promise);
          } finally {
            // WebSocket Write Binary End
            if (binaryFrame.isFinalFragment()) {
              DECORATE.onFrameEnd(handlerContext);
            }
          }
          return;
        }

        if (frame instanceof ContinuationWebSocketFrame) {
          ContinuationWebSocketFrame continuationWebSocketFrame =
              (ContinuationWebSocketFrame) frame;
          final AgentSpan span =
              DECORATE.onSendFrameStart(
                  handlerContext,
                  handlerContext.getMessageType(),
                  MESSAGE_TYPE_TEXT.equals(handlerContext.getMessageType())
                      ? continuationWebSocketFrame.text().length()
                      : continuationWebSocketFrame.content().readableBytes());
          try (final AgentScope scope = activateSpan(span)) {
            ctx.write(frame, promise);
          } finally {
            // WebSocket Write Binary End
            if (continuationWebSocketFrame.isFinalFragment()) {
              DECORATE.onFrameEnd(handlerContext);
            }
          }
          return;
        }

        if (frame instanceof CloseWebSocketFrame) {
          // WebSocket Closed by Server
          CloseWebSocketFrame closeFrame = (CloseWebSocketFrame) frame;
          int statusCode = closeFrame.statusCode();
          String reasonText = closeFrame.reasonText();
          channel.attr(WEBSOCKET_SENDER_HANDLER_CONTEXT).remove();
          final AgentSpan span =
              DECORATE.onSessionCloseIssued(handlerContext, reasonText, statusCode);
          try (final AgentScope scope = activateSpan(span)) {
            ctx.write(frame, promise);
          } finally {
            if (closeFrame.isFinalFragment()) {
              DECORATE.onFrameEnd(handlerContext);
            }
          }
          return;
        }
      }
    }
    // can be other messages we do not handle like ping, pong
    ctx.write(frame, promise);
  }
}
