package datadog.trace.correlation;

import datadog.trace.api.CorrelationIdentifier;
import datadog.trace.api.internal.InternalTracer;
import org.slf4j.MDC;

/**
 * Inject trace and span identifiers using SLF4J MDC. This injector works with SLF4J and Logback
 * API.
 */
@SuppressWarnings("unused")
class Slf4jCorrelationIdInjector extends AbstractCorrelationIdInjector {
  public Slf4jCorrelationIdInjector(InternalTracer tracer) {
    super(tracer);
  }

  @Override
  protected void afterScopeActivatedCallback() {
    MDC.put(CorrelationIdentifier.getTraceIdKey(), CorrelationIdentifier.getTraceId());
    MDC.put(CorrelationIdentifier.getSpanIdKey(), CorrelationIdentifier.getSpanId());
  }

  @Override
  protected void afterScopeClosedCallback() {
    MDC.remove(CorrelationIdentifier.getTraceIdKey());
    MDC.remove(CorrelationIdentifier.getSpanIdKey());
  }
}
