package datadog.trace.instrumentation.camel;

import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.CAMEL_ENDPOINT_SCHEME;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.CAMEL_ROUTE_ID;
import static datadog.trace.bootstrap.instrumentation.api.Tags.SPAN_KIND_INTERNAL;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.SpanPrototype;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;
import org.apache.camel.Route;

public final class CamelDecorator extends BaseDecorator {
  public static final CamelDecorator DECORATE = new CamelDecorator();
  public static final CharSequence ROUTE = UTF8BytesString.create("camel.route");

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"camel"};
  }

  @Override
  protected CharSequence spanType() {
    return null;
  }

  @Override
  protected CharSequence component() {
    return "camel";
  }

  @Override
  protected SpanPrototype buildSpanPrototype() {
    return SpanPrototype.builder()
        .extends_(super.buildSpanPrototype())
        .initKind(SPAN_KIND_INTERNAL)
        .build();
  }

  @Override
  protected void doAfterStart(AgentSpan span) {
    span.setMeasured(true);
    super.doAfterStart(span);
  }

  public void onRoute(AgentSpan span, Route route) {
    span.setResourceName(route.getId());
    span.setTag(CAMEL_ROUTE_ID, route.getId());
    String uri = route.getEndpoint().getEndpointUri();
    int separator = uri.indexOf(':');
    if (separator > 0) {
      // Endpoint paths and queries can contain application credentials and business data.
      span.setTag(CAMEL_ENDPOINT_SCHEME, uri.substring(0, separator));
    }
  }
}
