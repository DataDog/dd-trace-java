package datadog.trace.bootstrap.instrumentation.rmi;

import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.ClientDecorator;
import java.lang.reflect.Method;

public class RmiClientDecorator extends ClientDecorator {
  public static final CharSequence RMI_INVOKE =
      UTF8BytesString.create(
          SpanNaming.instance().namingSchema().client().operationForProtocol("rmi"));
  public static final CharSequence RMI_CLIENT = UTF8BytesString.create("rmi-client");
  public static final RmiClientDecorator DECORATE = new RmiClientDecorator();

  private static final DDCache<Method, CharSequence> METHOD_CACHE =
      DDCaches.newFixedSizeIdentityCache(32);

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"rmi", "rmi-client"};
  }

  @Override
  protected CharSequence spanType() {
    return InternalSpanTypes.RPC;
  }

  @Override
  protected CharSequence component() {
    return RMI_CLIENT;
  }

  @Override
  protected String service() {
    return null;
  }

  public AgentSpan onMethodInvocation(final AgentSpan span, final Method method) {
    span.setResourceName(spanNameForMethod(method));
    span.setTag(
        Tags.RPC_SERVICE,
        METHOD_CACHE.computeIfAbsent(method, m -> m.getDeclaringClass().getName()));
    return span;
  }
}
