package datadog.trace.instrumentation.netty38.server.websocket;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.decorator.WebsocketDecorator.DECORATE;
import static datadog.trace.bootstrap.instrumentation.websocket.HandlersExtractor.MESSAGE_TYPE_TEXT;

import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.websocket.HandlerContext;
import datadog.trace.instrumentation.netty38.ChannelTraceContext;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.ContinuationWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.WebSocketFrame;

public class WebSocketServerRequestTracingHandler extends SimpleChannelUpstreamHandler {

  private final ContextStore<Channel, ChannelTraceContext> contextStore;

  public WebSocketServerRequestTracingHandler(
      final ContextStore<Channel, ChannelTraceContext> contextStore) {
    this.contextStore = contextStore;
  }

  @Override
  public void messageReceived(ChannelHandlerContext ctx, MessageEvent event) throws Exception {
    Object frame = event.getMessage();
    if (frame instanceof WebSocketFrame) {
      Channel channel = ctx.getChannel();

      ChannelTraceContext traceContext = this.contextStore.get(channel);
      if (traceContext != null) {

        HandlerContext.Receiver receiverContext = traceContext.getReceiverHandlerContext();
        if (receiverContext == null) {
          HandlerContext.Sender sessionState = traceContext.getSenderHandlerContext();
          if (sessionState != null) {
            receiverContext =
                new HandlerContext.Receiver(
                    sessionState.getHandshakeSpan(), channel.getId().toString());
            traceContext.setReceiverHandlerContext(receiverContext);
          }
        }
        if (receiverContext != null) {
          if (frame instanceof TextWebSocketFrame) {
            // WebSocket Read Text Start
            TextWebSocketFrame textFrame = (TextWebSocketFrame) frame;

            final AgentSpan span =
                DECORATE.onReceiveFrameStart(
                    receiverContext, textFrame.getText(), textFrame.isFinalFragment());
            try (final AgentScope scope = activateSpan(span)) {
              ctx.sendUpstream(event);
              // WebSocket Read Text Start
            } finally {
              if (textFrame.isFinalFragment()) {
                traceContext.setReceiverHandlerContext(null);
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
                    binaryFrame.getBinaryData().array(),
                    binaryFrame.isFinalFragment());
            try (final AgentScope scope = activateSpan(span)) {
              ctx.sendUpstream(event);
            } finally {
              // WebSocket Read Binary End
              if (binaryFrame.isFinalFragment()) {
                traceContext.setReceiverHandlerContext(null);
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
                        ? continuationWebSocketFrame.getText()
                        : continuationWebSocketFrame.getBinaryData().array(),
                    continuationWebSocketFrame.isFinalFragment());
            try (final AgentScope scope = activateSpan(span)) {
              ctx.sendUpstream(event);
            } finally {
              if (continuationWebSocketFrame.isFinalFragment()) {
                traceContext.setReceiverHandlerContext(null);
                DECORATE.onFrameEnd(receiverContext);
              }
            }
            return;
          }

          if (frame instanceof CloseWebSocketFrame) {
            // WebSocket Closed by client
            CloseWebSocketFrame closeFrame = (CloseWebSocketFrame) frame;
            int statusCode = closeFrame.getStatusCode();
            String reasonText = closeFrame.getReasonText();
            traceContext.setSenderHandlerContext(null);
            traceContext.setReceiverHandlerContext(null);
            final AgentSpan span =
                DECORATE.onSessionCloseReceived(receiverContext, reasonText, statusCode);
            try (final AgentScope scope = activateSpan(span)) {
              ctx.sendUpstream(event);
              if (closeFrame.isFinalFragment()) {
                DECORATE.onFrameEnd(receiverContext);
              }
            }
            return;
          }
        }
      }
    }

    ctx.sendUpstream(event); // superclass does not throw
  }
}
