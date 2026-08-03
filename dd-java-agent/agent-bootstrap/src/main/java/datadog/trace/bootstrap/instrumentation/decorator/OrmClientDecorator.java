package datadog.trace.bootstrap.instrumentation.decorator;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

public abstract class OrmClientDecorator extends DatabaseClientDecorator {

  public abstract CharSequence entityName(final Object entity);

  public void onOperation(final AgentSpan span, final Object entity) {
    if (entity != null) {
      final CharSequence name = entityName(entity);
      if (name != null) {
        span.setResourceName(name);
      } // else we keep any existing resource.
    }
  }
}
