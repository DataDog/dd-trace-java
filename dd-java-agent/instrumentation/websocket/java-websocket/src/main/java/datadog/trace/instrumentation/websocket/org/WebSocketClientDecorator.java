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

public class WebSocketClientDecorator extends WebSocketDecorator {
  public static final WebSocketClientDecorator CLIENT_DECORATE = new WebSocketClientDecorator();

  @Override
  protected CharSequence spanKind() {
    return SPAN_KIND_CLIENT;
  }

  private static final String OPERATION_NAME = "websocket.handshake";

  public AgentScope startHandshakeSpan(WebSocketClient client) {
    String uri = client.getURI().toString();
    AgentSpan span = startSpan("websocket.handshake", OPERATION_NAME);
    span.setTag(Tags.HTTP_URL, uri);
    span.setTag(Tags.HTTP_METHOD, "GET");
    AgentScope scope = activateSpan(span);
    defaultPropagator().inject(scope.context(), client, SETTER);
    afterStart(span);
    return scope;
  }

  public void onHandshakeSuccess(AgentSpan span, int httpStatus) {
    span.setTag(Tags.HTTP_STATUS, httpStatus);
    span.setTag("websocket.handshake.success", true);
  }
}
