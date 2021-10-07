package datadog.trace.instrumentation.restlet;

import static datadog.trace.bootstrap.instrumentation.decorator.http.HttpResourceDecorator.HTTP_RESOURCE_DECORATOR;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;
import java.lang.reflect.Method;
import org.restlet.engine.header.Header;
import org.restlet.resource.ServerResource;
import org.restlet.util.Series;

public class ResourceDecorator extends BaseDecorator {

  public static final CharSequence RESTLET_CONTROLLER =
      UTF8BytesString.create("restlet-controller");

  public static final String RESTLET_ROUTE = "datadog.trace.instrumentation.restlet.route";

  public static ResourceDecorator DECORATE = new ResourceDecorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"restlet-http"};
  }

  @Override
  protected CharSequence spanType() {
    return null;
  }

  @Override
  protected CharSequence component() {
    return RESTLET_CONTROLLER;
  }

  public void onRestletSpan(
      final AgentSpan span,
      final AgentSpan parent,
      final ServerResource serverResource,
      final Method method) {
    Series<Header> headers =
        (Series<Header>)
            serverResource.getRequest().getAttributes().get("org.restlet.http.headers");
    String route = headers.getFirstValue(RESTLET_ROUTE);

    span.setSpanType(InternalSpanTypes.HTTP_SERVER);

    // When restlet-http is the root, we want to name using the path, otherwise use
    // class.method.
    final boolean isRootScope = parent == null;
    if (isRootScope) {
      HTTP_RESOURCE_DECORATOR.withRoute(span, serverResource.getMethod().getName(), route);
    } else {
      span.setResourceName(DECORATE.spanNameForMethod(method));

      if (parent == parent.getLocalRootSpan()) {
        HTTP_RESOURCE_DECORATOR.withRoute(parent, serverResource.getMethod().getName(), route);
      }
    }
  }
}
