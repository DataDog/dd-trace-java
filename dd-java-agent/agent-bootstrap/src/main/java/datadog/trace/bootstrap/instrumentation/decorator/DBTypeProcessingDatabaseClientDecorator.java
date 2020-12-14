package datadog.trace.bootstrap.instrumentation.decorator;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

public abstract class DBTypeProcessingDatabaseClientDecorator<CONNECTION>
    extends DatabaseClientDecorator<CONNECTION> {

  @Override
  public AgentSpan afterStart(AgentSpan span) {
    processDatabaseType(span, dbType());
    return super.afterStart(span);
  }
}
