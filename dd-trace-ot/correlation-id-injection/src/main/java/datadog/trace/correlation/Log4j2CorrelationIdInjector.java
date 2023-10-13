package datadog.trace.correlation;

import datadog.trace.api.CorrelationIdentifier;
import datadog.trace.api.internal.InternalTracer;
import org.apache.logging.log4j.ThreadContext;

/** Inject trace and span identifiers using Log4j2 ThreadContext. */
@SuppressWarnings("unused")
class Log4j2CorrelationIdInjector extends AbstractCorrelationIdInjector {
  public Log4j2CorrelationIdInjector(InternalTracer tracer) {
    super(tracer);
  }

  @Override
  protected void afterScopeActivatedCallback() {
    ThreadContext.put(CorrelationIdentifier.getTraceIdKey(), CorrelationIdentifier.getTraceId());
    ThreadContext.put(CorrelationIdentifier.getSpanIdKey(), CorrelationIdentifier.getSpanId());
  }

  @Override
  protected void afterScopeClosedCallback() {
    ThreadContext.remove(CorrelationIdentifier.getTraceIdKey());
    ThreadContext.remove(CorrelationIdentifier.getSpanIdKey());
  }
}
