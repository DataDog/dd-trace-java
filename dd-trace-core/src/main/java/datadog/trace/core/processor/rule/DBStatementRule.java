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
    final Object dbStatementValue = tags.get(Tags.DB_STATEMENT);

    if (dbStatementValue instanceof String) {
      // Special case: Mongo
      // Skip the decorators
      if (tags.containsKey(Tags.COMPONENT) && "java-mongo".equals(tags.get(Tags.COMPONENT))) {
        return;
      }
      final String statement = (String) dbStatementValue;
      if (!statement.isEmpty()) {
        span.setResourceName(statement);
      }
    }

    if (tags.containsKey(Tags.DB_STATEMENT)) {
      span.setTag(Tags.DB_STATEMENT, (String) null); // Remove the tag
    }
  }
}
