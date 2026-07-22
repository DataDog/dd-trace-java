package datadog.trace.bootstrap.instrumentation.decorator;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

public abstract class DBTypeProcessingDatabaseClientDecorator<CONNECTION>
    extends DatabaseClientDecorator<CONNECTION> {

  @Override
  public void afterStart(AgentSpan span) {
    processDatabaseType(span, dbType());
    super.afterStart(span);
  }
}
