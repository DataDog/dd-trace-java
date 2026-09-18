package datadog.trace.instrumentation.websocket.org;

import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_SERVER;

public class WebSocketServerDecorator extends WebSocketDecorator {
  public static final WebSocketServerDecorator SERVER_DECORATOR = new WebSocketServerDecorator();

  @Override
  protected CharSequence spanKind() {
    return SPAN_KIND_SERVER;
  }
}
