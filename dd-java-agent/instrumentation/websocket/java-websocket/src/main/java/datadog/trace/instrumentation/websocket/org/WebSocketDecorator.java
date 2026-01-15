package datadog.trace.instrumentation.websocket.org;

import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.extractContextAndGetSpanContext;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.websocket.HandlersExtractor.MESSAGE_TYPE_BINARY;
import static datadog.trace.bootstrap.instrumentation.websocket.HandlersExtractor.MESSAGE_TYPE_TEXT;
import static datadog.trace.instrumentation.websocket.org.WebsocketExtractAdapter.GETTER;

import datadog.trace.api.DDSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;
import java.nio.ByteBuffer;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.Handshakedata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class WebSocketDecorator extends BaseDecorator {
  private static final Logger log = LoggerFactory.getLogger(WebSocketDecorator.class);
  public static final CharSequence WEBSOCKET = UTF8BytesString.create("org-java-websocket");
  public static final CharSequence WEBSOCKET_OPEN = UTF8BytesString.create("websocket.open");
  public static final CharSequence WEBSOCKET_RECEIVE = UTF8BytesString.create("websocket.receive");
  public static final CharSequence WEBSOCKET_SEND = UTF8BytesString.create("websocket.send");
  public static final CharSequence WEBSOCKET_CLOSE = UTF8BytesString.create("websocket.close");
  private static final String[] INSTRUMENTATION_NAMES = {WEBSOCKET.toString()};
  private static final String REMOTE_ADDRESS = "remote.address";
  private static final String MESSAGE_TYPE = "message.type";
  private static final String MESSAGE_SIZE = "message.size";
  private static final String MESSAGE_REMOTE = "message.remote";
  private static final String MESSAGE_CODE = "message.code";
  private static final String MESSAGE_REASON = "message.reason";

  @Override
  protected String[] instrumentationNames() {
    return INSTRUMENTATION_NAMES;
  }

  @Override
  protected CharSequence spanType() {
    return DDSpanTypes.WEBSOCKET;
  }

  @Override
  protected CharSequence component() {
    return WEBSOCKET;
  }
  protected abstract CharSequence spanKind();

  public AgentSpan open(WebSocket conn, Handshakedata handshake) {
    
    AgentSpanContext parentContext = extractContextAndGetSpanContext(handshake, GETTER);
    AgentSpan span = startSpan(instrumentationNames()[0],WEBSOCKET_OPEN,parentContext);
    span.setTag(REMOTE_ADDRESS, conn.getRemoteSocketAddress().toString());
    afterStart(span);
    return span;
  }

  public AgentSpan onMessage(Object message,AgentSpanContext spanContext) {
    AgentSpan span =  startSpan(instrumentationNames()[0],WEBSOCKET_RECEIVE,spanContext);
    CharSequence messageType;
    int msgSize;
    if(message instanceof ByteBuffer){
      messageType = MESSAGE_TYPE_BINARY;
      msgSize = ((ByteBuffer) message).remaining();
    }else{
      messageType = MESSAGE_TYPE_TEXT;
      msgSize = ((String) message).length();
    }
    span.setTag(MESSAGE_SIZE, msgSize);
    span.setTag(MESSAGE_TYPE, messageType);
    afterStart(span);
    return span;
  }

  public AgentSpan send(WebsocketAgentSpanContext spanContext) {
    AgentSpan span;
    if(spanContext==null){
      span =  startSpan(instrumentationNames()[0],WEBSOCKET_SEND);
    }else{
      span =  startSpan(instrumentationNames()[0],WEBSOCKET_SEND,spanContext.getSpanContext());
      span.setTag(Tags.SPAN_KIND, spanContext.getSpanKind());
    }
    return span;
  }

  public AgentSpan onClose(int code, String reason, boolean remote,AgentSpanContext spanContext) {
    AgentSpan span =  startSpan(instrumentationNames()[0],WEBSOCKET_CLOSE,spanContext);
    span.setTag(MESSAGE_REASON, reason);
    span.setTag(MESSAGE_CODE, code);
    span.setTag(MESSAGE_REMOTE, remote);
    afterStart(span);
    return span;
  }

  @Override
  public AgentSpan afterStart(AgentSpan span) {
    span.setTag(Tags.SPAN_KIND, spanKind());
    return super.afterStart(span);
  }
}
