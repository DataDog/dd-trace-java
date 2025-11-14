package datadog.trace.instrumentation.jaxws1;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;

public class WebServiceDecorator extends BaseDecorator {
  public static final WebServiceDecorator DECORATE = new WebServiceDecorator();

  public static final CharSequence JAX_WS_REQUEST = UTF8BytesString.create("jax-ws.request");
  public static final CharSequence JAX_WS_ENDPOINT = UTF8BytesString.create("jax-ws-endpoint");

  private WebServiceDecorator() {}

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"jax-ws"};
  }

  @Override
  protected CharSequence spanType() {
    return InternalSpanTypes.SOAP;
  }

  @Override
  protected CharSequence component() {
    return JAX_WS_ENDPOINT;
  }

  public void onJaxWsSpan(final AgentSpan span, final Class<?> target, final String method) {
    span.setResourceName(spanNameForMethod(target, method));
  }
}
