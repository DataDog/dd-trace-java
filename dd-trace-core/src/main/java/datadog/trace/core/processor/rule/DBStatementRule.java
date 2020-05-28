package datadog.trace.core.processor.rule;

import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.DDSpan;
import datadog.trace.core.processor.TraceProcessor;
import java.util.Collection;
import java.util.Map;

/**
 * Converts db.statement tag to resource name. This is later set to sql.query by the datadog agent
 * after obfuscation.
 */
public class DBStatementRule implements TraceProcessor.Rule {
  @Override
  public String[] aliases() {
    return new String[] {"DBStatementAsResourceName"};
  }

  @Override
  public void processSpan(
      final DDSpan span, final Map<String, Object> tags, final Collection<DDSpan> trace) {
    // Special case: Mongo
    // Skip the decorators
    if (!"java-mongo".equals(tags.get(Tags.COMPONENT))) {
      final Object dbStatementValue = span.getAndRemoveTag(Tags.DB_STATEMENT);
      if (dbStatementValue instanceof String) {
        final String statement = (String) dbStatementValue;
        if (!statement.isEmpty()) {
          span.setResourceName(statement);
        }
      }
    }
  }
}
