package datadog.trace.bootstrap.instrumentation.decorator;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import edu.umd.cs.findbugs.annotations.NonNull;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
public abstract class DBTypeProcessingDatabaseClientDecorator<CONNECTION>
    extends DatabaseClientDecorator<CONNECTION> {

  @Override
  protected void doAfterStart(AgentSpan span) {
    processDatabaseType(span, dbType());
    super.doAfterStart(span);
  }
}
