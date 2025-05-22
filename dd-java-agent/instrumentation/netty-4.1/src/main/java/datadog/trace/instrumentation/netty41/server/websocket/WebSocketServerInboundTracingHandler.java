package datadog.trace.instrumentation.netty41.server.websocket;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.decorator.WebsocketDecorator.DECORATE;
import static datadog.trace.bootstrap.instrumentation.websocket.HandlersExtractor.MESSAGE_TYPE_TEXT;
import static datadog.trace.instrumentation.netty41.AttributeKeys.WEBSOCKET_RECEIVER_HANDLER_CONTEXT;
import static datadog.trace.instrumentation.netty41.AttributeKeys.WEBSOCKET_SENDER_HANDLER_CONTEXT;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.websocket.HandlerContext;
import io.netty.channel.*;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.ContinuationWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;

@ChannelHandler.Sharable
public class WebSocketServerInboundTracingHandler extends ChannelInboundHandlerAdapter {
  public static WebSocketServerInboundTracingHandler INSTANCE =
      new WebSocketServerInboundTracingHandler();

  @Override
  public void channelRead(ChannelHandlerContext ctx, Object frame) {
    if (frame instanceof WebSocketFrame) {
      Channel channel = ctx.channel();
      HandlerContext.Receiver receiverContext =
          channel.attr(WEBSOCKET_RECEIVER_HANDLER_CONTEXT).get();
      if (receiverContext == null) {
        HandlerContext.Sender sessionState = channel.attr(WEBSOCKET_SENDER_HANDLER_CONTEXT).get();
        if (sessionState != null) {
          receiverContext =
              new HandlerContext.Receiver(
                  sessionState.getHandshakeSpan(), channel.id().asShortText());
          channel.attr(WEBSOCKET_RECEIVER_HANDLER_CONTEXT).set(receiverContext);
        }
      }
      if (receiverContext != null) {
        if (frame instanceof TextWebSocketFrame) {
          // WebSocket Read Text Start
          TextWebSocketFrame textFrame = (TextWebSocketFrame) frame;

          final AgentSpan span =
              DECORATE.onReceiveFrameStart(
                  receiverContext, textFrame.text(), textFrame.isFinalFragment());
          try (final AgentScope scope = activateSpan(span)) {
            ctx.fireChannelRead(textFrame);
            // WebSocket Read Text Start
          } finally {
            if (textFrame.isFinalFragment()) {
              channel.attr(WEBSOCKET_RECEIVER_HANDLER_CONTEXT).remove();
              DECORATE.onFrameEnd(receiverContext);
            }
          }
          return;
        }

        if (frame instanceof BinaryWebSocketFrame) {
          // WebSocket Read Binary Start
          BinaryWebSocketFrame binaryFrame = (BinaryWebSocketFrame) frame;
          final AgentSpan span =
              DECORATE.onReceiveFrameStart(
                  receiverContext,
                  binaryFrame.content().nioBuffer(),
                  binaryFrame.isFinalFragment());
          try (final AgentScope scope = activateSpan(span)) {
            ctx.fireChannelRead(binaryFrame);
          } finally {
            // WebSocket Read Binary End
            if (binaryFrame.isFinalFragment()) {
              channel.attr(WEBSOCKET_RECEIVER_HANDLER_CONTEXT).remove();
              DECORATE.onFrameEnd(receiverContext);
            }
          }

          return;
        }

        if (frame instanceof ContinuationWebSocketFrame) {
          ContinuationWebSocketFrame continuationWebSocketFrame =
              (ContinuationWebSocketFrame) frame;
          final AgentSpan span =
              DECORATE.onReceiveFrameStart(
                  receiverContext,
                  MESSAGE_TYPE_TEXT.equals(receiverContext.getMessageType())
                      ? continuationWebSocketFrame.text()
                      : continuationWebSocketFrame.content().nioBuffer(),
                  continuationWebSocketFrame.isFinalFragment());
          try (final AgentScope scope = activateSpan(span)) {
            ctx.fireChannelRead(continuationWebSocketFrame);
          } finally {
            if (continuationWebSocketFrame.isFinalFragment()) {
              channel.attr(WEBSOCKET_RECEIVER_HANDLER_CONTEXT).remove();
              DECORATE.onFrameEnd(receiverContext);
            }
          }
          return;
        }

        if (frame instanceof CloseWebSocketFrame) {
          // WebSocket Closed by client
          CloseWebSocketFrame closeFrame = (CloseWebSocketFrame) frame;
          int statusCode = closeFrame.statusCode();
          String reasonText = closeFrame.reasonText();
          channel.attr(WEBSOCKET_SENDER_HANDLER_CONTEXT).remove();
          channel.attr(WEBSOCKET_RECEIVER_HANDLER_CONTEXT).remove();
          final AgentSpan span =
              DECORATE.onSessionCloseReceived(receiverContext, reasonText, statusCode);
          try (final AgentScope scope = activateSpan(span)) {
            ctx.fireChannelRead(closeFrame);
            if (closeFrame.isFinalFragment()) {
              DECORATE.onFrameEnd(receiverContext);
            }
          }
          return;
        }
      }
    }
    // can be other messages we do not handle like ping, pong
    ctx.fireChannelRead(frame);
  }
}
