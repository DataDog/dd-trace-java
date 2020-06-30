package datadog.trace.core.processor.rule;

import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.DDSpan;
import datadog.trace.core.processor.TraceProcessor;

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
  public void processSpan(final DDSpan span) {
    // Special case: Mongo
    // Skip the decorators
    if (!"java-mongo".equals(span.getTag(Tags.COMPONENT))) {
      final Object dbStatementValue = span.getAndRemoveTag(Tags.DB_STATEMENT);
      if (dbStatementValue instanceof CharSequence) {
        final CharSequence statement = (CharSequence) dbStatementValue;
        if (statement.length() != 0) {
          span.setResourceName(statement);
        }
      }
    }
  }
}
