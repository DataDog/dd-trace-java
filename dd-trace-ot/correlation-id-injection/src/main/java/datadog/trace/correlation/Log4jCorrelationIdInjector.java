package datadog.trace.correlation;

import datadog.trace.api.CorrelationIdentifier;
import datadog.trace.api.internal.InternalTracer;
import org.apache.log4j.MDC;

/**
 * Inject trace and span identifiers using Log4j 1 MDC. MDC does not work from Java 9+. Check <a
 * href="https://blogs.apache.org/logging/entry/moving_on_to_log4j_2">Apache logging blog</a> for
 * more details.
 */
@SuppressWarnings("unused")
class Log4jCorrelationIdInjector extends AbstractCorrelationIdInjector {
  public Log4jCorrelationIdInjector(InternalTracer tracer) {
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
