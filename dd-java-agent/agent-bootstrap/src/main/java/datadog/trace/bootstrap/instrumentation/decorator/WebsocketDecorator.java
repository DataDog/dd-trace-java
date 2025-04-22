package datadog.trace.bootstrap.instrumentation.decorator;

import static datadog.trace.api.DDTags.DECISION_MAKER_INHERITED;
import static datadog.trace.api.DDTags.DECISION_MAKER_RESOURCE;
import static datadog.trace.api.DDTags.DECISION_MAKER_SERVICE;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.WEBSOCKET_CLOSE_CODE;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.WEBSOCKET_CLOSE_REASON;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.WEBSOCKET_MESSAGE_FRAMES;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.WEBSOCKET_MESSAGE_LENGTH;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.WEBSOCKET_MESSAGE_RECEIVE_TIME;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.WEBSOCKET_MESSAGE_TYPE;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.WEBSOCKET_SESSION_ID;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_CONSUMER;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_PRODUCER;

import datadog.trace.api.Config;
import datadog.trace.api.time.SystemTimeSource;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.NotSampledSpanContext;
import datadog.trace.bootstrap.instrumentation.api.SpanAttributes;
import datadog.trace.bootstrap.instrumentation.api.SpanLink;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.websocket.HandlerContext;
import javax.annotation.Nonnull;

public class WebsocketDecorator extends BaseDecorator {
  private static final CharSequence WEBSOCKET = UTF8BytesString.create("websocket");
  private static final String[] INSTRUMENTATION_NAMES = {WEBSOCKET.toString()};
  private static final CharSequence WEBSOCKET_RECEIVE = UTF8BytesString.create("websocket.receive");
  private static final CharSequence WEBSOCKET_SEND = UTF8BytesString.create("websocket.send");
  private static final CharSequence WEBSOCKET_CLOSE = UTF8BytesString.create("websocket.close");

  private static final SpanAttributes SPAN_ATTRIBUTES_RECEIVE =
      SpanAttributes.builder().put("dd.kind", "executed_from").build();
  private static final SpanAttributes SPAN_ATTRIBUTES_SEND =
      SpanAttributes.builder().put("dd.kind", "resuming").build();

  public static final WebsocketDecorator DECORATE = new WebsocketDecorator();

  @Override
  protected String[] instrumentationNames() {
    return INSTRUMENTATION_NAMES;
  }

  @Override
  protected CharSequence spanType() {
    return InternalSpanTypes.WEBSOCKET;
  }

  @Override
  protected CharSequence component() {
    return WEBSOCKET;
  }

  @Override
  public AgentSpan afterStart(AgentSpan span) {
    return super.afterStart(span).setMeasured(true);
  }

  @Nonnull
  public AgentSpan onReceiveFrameStart(
      final HandlerContext.Receiver handlerContext, final Object data, boolean partialDelivery) {
    handlerContext.recordChunkData(data, partialDelivery);
    return onFrameStart(
        WEBSOCKET_RECEIVE, SPAN_KIND_CONSUMER, handlerContext, SPAN_ATTRIBUTES_RECEIVE, true);
  }

  @Nonnull
  public AgentSpan onSessionCloseIssued(
      final HandlerContext.Sender handlerContext, CharSequence closeReason, int closeCode) {
    return onFrameStart(
            WEBSOCKET_CLOSE, SPAN_KIND_PRODUCER, handlerContext, SPAN_ATTRIBUTES_SEND, false)
        .setTag(WEBSOCKET_CLOSE_CODE, closeCode)
        .setTag(WEBSOCKET_CLOSE_REASON, closeReason);
  }

  @Nonnull
  public AgentSpan onSessionCloseReceived(
      final HandlerContext.Receiver handlerContext, CharSequence closeReason, int closeCode) {
    return onFrameStart(
            WEBSOCKET_CLOSE, SPAN_KIND_CONSUMER, handlerContext, SPAN_ATTRIBUTES_RECEIVE, true)
        .setTag(WEBSOCKET_CLOSE_CODE, closeCode)
        .setTag(WEBSOCKET_CLOSE_REASON, closeReason);
  }

  @Nonnull
  public AgentSpan onSendFrameStart(
      final HandlerContext.Sender handlerContext, final CharSequence msgType, final int msgSize) {
    handlerContext.recordChunkData(msgType, msgSize);
    return onFrameStart(
        WEBSOCKET_SEND, SPAN_KIND_PRODUCER, handlerContext, SPAN_ATTRIBUTES_SEND, false);
  }

  public void onFrameEnd(final HandlerContext handlerContext) {
    if (handlerContext == null) {
      return;
    }
    final AgentSpan wsSpan = handlerContext.getWebsocketSpan();
    if (wsSpan == null) {
      return;
    }
    try {
      final long startTime = handlerContext.getFirstFrameTimestamp();
      if (startTime > 0) {
        wsSpan.setTag(
            WEBSOCKET_MESSAGE_RECEIVE_TIME,
            SystemTimeSource.INSTANCE.getCurrentTimeNanos() - startTime);
      }
      final long chunks = handlerContext.getMsgChunks();
      if (chunks > 0) {
        wsSpan.setTag(WEBSOCKET_MESSAGE_FRAMES, chunks);
        wsSpan.setTag(WEBSOCKET_MESSAGE_LENGTH, handlerContext.getMsgSize());
        wsSpan.setTag(WEBSOCKET_MESSAGE_TYPE, handlerContext.getMessageType());
      }
      (beforeFinish(wsSpan)).finish();
    } finally {
      handlerContext.reset();
    }
  }

  private AgentSpan onFrameStart(
      final CharSequence operationName,
      final CharSequence spanKind,
      final HandlerContext handlerContext,
      final SpanAttributes linkAttributes,
      boolean traceStarter) {
    AgentSpan wsSpan = handlerContext.getWebsocketSpan();
    if (wsSpan == null) {
      final Config config = Config.get();
      final AgentSpan handshakeSpan = handlerContext.getHandshakeSpan();
      boolean inheritSampling = config.isWebsocketMessagesInheritSampling();
      boolean useDedicatedTraces = config.isWebsocketMessagesSeparateTraces();
      if (traceStarter) {
        if (useDedicatedTraces) {
          wsSpan = startSpan(WEBSOCKET.toString(), operationName, null);
          if (inheritSampling) {
            wsSpan.copyPropagationAndBaggage(handshakeSpan);
            wsSpan.setTag(DECISION_MAKER_INHERITED, 1);
            wsSpan.setTag(DECISION_MAKER_SERVICE, handshakeSpan.getServiceName());
            wsSpan.setTag(DECISION_MAKER_RESOURCE, handshakeSpan.getResourceName());
          }
        } else {
          wsSpan = startSpan(WEBSOCKET.toString(), operationName, handshakeSpan.context());
        }
      } else {
        wsSpan = startSpan(WEBSOCKET.toString(), operationName);
      }
      handlerContext.setWebsocketSpan(wsSpan);
      afterStart(wsSpan);
      wsSpan.setTag(SPAN_KIND, spanKind);
      wsSpan.setResourceName(handlerContext.getWsResourceName());
      // carry over peer information for inferred services
      final String handshakePeerAddress = (String) handshakeSpan.getTag(Tags.PEER_HOSTNAME);
      if (handshakePeerAddress != null) {
        wsSpan.setTag(Tags.PEER_HOSTNAME, handshakePeerAddress);
      }
      if (config.isWebsocketTagSessionId()) {
        wsSpan.setTag(WEBSOCKET_SESSION_ID, handlerContext.getSessionId());
      }
      if (useDedicatedTraces || !traceStarter) {
        // the link is not added if the user wants to have receive frames on the same trace as the
        // handshake
        wsSpan.addLink(
            SpanLink.from(
                inheritSampling
                    ? handshakeSpan.context()
                    : new NotSampledSpanContext(handshakeSpan.context()),
                SpanLink.DEFAULT_FLAGS,
                "",
                linkAttributes));
      }
    }
    return wsSpan;
  }
}
