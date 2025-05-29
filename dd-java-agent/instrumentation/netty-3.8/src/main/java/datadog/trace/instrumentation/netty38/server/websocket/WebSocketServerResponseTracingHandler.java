package datadog.trace.instrumentation.netty38.server.websocket;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.decorator.WebsocketDecorator.DECORATE;
import static datadog.trace.bootstrap.instrumentation.websocket.HandlersExtractor.MESSAGE_TYPE_BINARY;
import static datadog.trace.bootstrap.instrumentation.websocket.HandlersExtractor.MESSAGE_TYPE_TEXT;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.websocket.HandlerContext;
import datadog.trace.instrumentation.netty38.ChannelTraceContext;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelDownstreamHandler;
import org.jboss.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.ContinuationWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.WebSocketFrame;

public class WebSocketServerResponseTracingHandler extends SimpleChannelDownstreamHandler {

  private final ContextStore<Channel, ChannelTraceContext> contextStore;

  public WebSocketServerResponseTracingHandler(
      final ContextStore<Channel, ChannelTraceContext> contextStore) {
    this.contextStore = contextStore;
  }

  @Override
  public void writeRequested(ChannelHandlerContext ctx, MessageEvent event) throws Exception {
    Object frame = event.getMessage();
    if (frame instanceof WebSocketFrame) {
      Channel channel = ctx.getChannel();

      ChannelTraceContext traceContext = this.contextStore.get(channel);
      if (traceContext != null) {
        HandlerContext.Sender handlerContext = traceContext.getSenderHandlerContext();
        if (handlerContext != null) {

          if (frame instanceof TextWebSocketFrame) {
            // WebSocket Write Text Start
            TextWebSocketFrame textFrame = (TextWebSocketFrame) frame;
            final AgentSpan span =
                DECORATE.onSendFrameStart(
                    handlerContext, MESSAGE_TYPE_TEXT, textFrame.getText().length());
            try (final AgentScope scope = activateSpan(span)) {
              ctx.sendDownstream(event);
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
                    handlerContext,
                    MESSAGE_TYPE_BINARY,
                    binaryFrame.getBinaryData().readableBytes());
            try (final AgentScope scope = activateSpan(span)) {
              ctx.sendDownstream(event);
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
                        ? continuationWebSocketFrame.getText().length()
                        : continuationWebSocketFrame.getBinaryData().readableBytes());
            try (final AgentScope scope = activateSpan(span)) {
              ctx.sendDownstream(event);
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
            int statusCode = closeFrame.getStatusCode();
            String reasonText = closeFrame.getReasonText();
            traceContext.setSenderHandlerContext(null);
            final AgentSpan span =
                DECORATE.onSessionCloseIssued(handlerContext, reasonText, statusCode);
            try (final AgentScope scope = activateSpan(span)) {
              ctx.sendDownstream(event);
            } finally {
              if (closeFrame.isFinalFragment()) {
                DECORATE.onFrameEnd(handlerContext);
              }
            }
            return;
          }
        }
      }
    }
    // can be other messages we do not handle like ping, pong
    ctx.sendDownstream(event);
  }
}
