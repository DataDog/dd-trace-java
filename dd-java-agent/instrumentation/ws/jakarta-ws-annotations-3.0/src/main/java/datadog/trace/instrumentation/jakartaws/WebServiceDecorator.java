package datadog.trace.instrumentation.jakartaws;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;

public class WebServiceDecorator extends BaseDecorator {
  public static final WebServiceDecorator DECORATE = new WebServiceDecorator();

  public static final CharSequence JAKARTA_WS_REQUEST =
      UTF8BytesString.create("jakarta-ws.request");
  public static final CharSequence JAKARTA_WS_ENDPOINT =
      UTF8BytesString.create("jakarta-ws-endpoint");

  private WebServiceDecorator() {}

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"jakarta-ws"};
  }

  @Override
  protected CharSequence spanType() {
    return InternalSpanTypes.SOAP;
  }

  @Override
  protected CharSequence component() {
    return JAKARTA_WS_ENDPOINT;
  }

  public void onJakartaWsSpan(final AgentSpan span, final Class<?> target, final String method) {
    span.setResourceName(spanNameForMethod(target, method));
  }
}
