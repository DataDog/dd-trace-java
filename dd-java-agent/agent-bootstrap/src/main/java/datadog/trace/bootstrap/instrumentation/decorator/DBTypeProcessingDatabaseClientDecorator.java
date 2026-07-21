package datadog.trace.bootstrap.instrumentation.decorator;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;

public abstract class DBTypeProcessingDatabaseClientDecorator<CONNECTION>
    extends DatabaseClientDecorator<CONNECTION> {

  @Override
  protected void doAfterStart(AgentSpan span) {
    processDatabaseType(span, dbType());
    super.doAfterStart(span);
  }
}
