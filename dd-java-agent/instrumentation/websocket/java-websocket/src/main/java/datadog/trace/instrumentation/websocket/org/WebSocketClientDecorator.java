package datadog.trace.instrumentation.websocket.org;

import static datadog.context.propagation.Propagators.defaultPropagator;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_CLIENT;
import static datadog.trace.instrumentation.websocket.org.WebsocketHeaderInjector.SETTER;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import org.java_websocket.client.WebSocketClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WebSocketClientDecorator extends WebSocketDecorator {
  private static final Logger log = LoggerFactory.getLogger(WebSocketClientDecorator.class);
  public static final WebSocketClientDecorator CLIENT_DECORATE = new WebSocketClientDecorator();
  @Override
  protected CharSequence spanKind() {
    return SPAN_KIND_CLIENT;
  }

  private static final String OPERATION_NAME = "websocket.handshake";

  /**
   * 在 connect() 调用时开始 Span
   */
  public AgentScope startHandshakeSpan(WebSocketClient client) {
    // 获取 WebSocket URI
    String uri = client.getURI().toString();
    AgentSpan span = startSpan(OPERATION_NAME);
    span.setTag(Tags.HTTP_URL, uri);
    span.setTag(Tags.HTTP_METHOD, "GET"); // WebSocket 握手是 GET
    AgentScope scope = activateSpan(span);
    defaultPropagator().inject(scope.context(), client, SETTER);
    afterStart(span);
    return scope;
  }

  /**
   * 在 onOpen 中标记握手成功并结束 Span
   */
  public void onHandshakeSuccess(AgentSpan span, int httpStatus) {
    span.setTag(Tags.HTTP_STATUS, httpStatus); // 101
    span.setTag("websocket.handshake.success", true);

  }
}
