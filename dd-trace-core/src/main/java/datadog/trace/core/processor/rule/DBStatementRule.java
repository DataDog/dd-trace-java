package datadog.trace.core.processor.rule;

import static datadog.trace.bootstrap.instrumentation.api.DDComponents.JAVA_JDBC_PREPARED_STATEMENT;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Sets;
import datadog.trace.bootstrap.instrumentation.api.DDComponents;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.DDSpan;
import datadog.trace.core.processor.TraceProcessor;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

/**
 * Converts db.statement tag to resource name. This is later set to sql.query by the datadog agent
 * after obfuscation.
 */
public class DBStatementRule implements TraceProcessor.Rule {

  private static final Set<String> RESOURCE_NAME_CACHING_COMPONENTS =
    Sets.newHashSet(JAVA_JDBC_PREPARED_STATEMENT);

  private final LoadingCache<String, byte[]> preparedStatementCache =
      CacheBuilder.newBuilder()
          .maximumSize(100)
          .concurrencyLevel(1)
          .build(
              new CacheLoader<String, byte[]>() {
                @Override
                public byte[] load(String key) throws Exception {
                  return key.getBytes(StandardCharsets.UTF_8);
                }
              });

  @Override
  public String[] aliases() {
    return new String[] {"DBStatementAsResourceName"};
  }

  @Override
  public void processSpan(
      final DDSpan span, final Map<String, Object> tags, final Collection<DDSpan> trace) {
    // Special case: Mongo
    // Skip the decorators
    Object component = tags.get(Tags.COMPONENT);
    if (!"java-mongo".equals(component)) {
      final Object dbStatementValue = span.getAndRemoveTag(Tags.DB_STATEMENT);
      if (dbStatementValue instanceof String) {
        final String statement = (String) dbStatementValue;
        if (!statement.isEmpty()) {
          span.setResourceName(statement);
        }
      }
    }
    if (RESOURCE_NAME_CACHING_COMPONENTS.contains(component)) {
      try {
        span.setResourceName(preparedStatementCache.get(span.getResourceName()));
      } catch (ExecutionException e) {
        // oh well, we'll just do the conversion the hard way later
      }
    }
  }
}
