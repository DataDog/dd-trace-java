package datadog.trace.instrumentation.servlet.dispatcher;

import static datadog.context.propagation.Propagators.defaultPropagator;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.traceConfig;

import datadog.context.Context;
import datadog.context.propagation.CarrierSetter;
import datadog.trace.api.datastreams.DataStreamsContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.BaseDecorator;
import javax.servlet.ServletException;

public class RequestDispatcherDecorator extends BaseDecorator {
  public static final RequestDispatcherDecorator DECORATE = new RequestDispatcherDecorator();
  public static final CharSequence JAVA_WEB_SERVLET_DISPATCHER =
      UTF8BytesString.create("java-web-servlet-dispatcher");
  public static final String DD_CONTEXT_PATH_ATTRIBUTE = "datadog.context.path";
  public static final String DD_SERVLET_PATH_ATTRIBUTE = "datadog.servlet.path";

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"servlet", "servlet-dispatcher"};
  }

  @Override
  protected CharSequence spanType() {
    return null;
  }

  @Override
  protected CharSequence component() {
    return JAVA_WEB_SERVLET_DISPATCHER;
  }

  @Override
  public AgentSpan onError(final AgentSpan span, final Throwable throwable) {
    if (throwable instanceof ServletException && throwable.getCause() != null) {
      super.onError(span, throwable.getCause());
    } else {
      super.onError(span, throwable);
    }
    return span;
  }

  public <C> void injectContext(Context context, final C request, CarrierSetter<C> setter) {
    // Add additional default DSM context for HTTP clients if missing but DSM is enabled
    if (traceConfig().isDataStreamsEnabled()) {
      context = context.with(DataStreamsContext.forHttpClient());
    }
    // Inject context into carrier
    defaultPropagator().inject(context, request, setter);
  }
}
