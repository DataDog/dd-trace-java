package datadog.trace.instrumentation.cassandra4;

import static datadog.trace.api.Config.DBM_PROPAGATION_MODE_FULL;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.DBM_TRACE_INJECTED;

import datadog.trace.api.Config;
import datadog.trace.api.propagation.W3CTraceParent;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.dbm.SharedDBCommenter;

/**
 * Utility class for Cassandra Database Monitoring (DBM) comment injection. When DBM propagation is
 * enabled, this injects trace context as a CQL comment prepended to the query string so that the
 * Datadog database agent can correlate queries back to traces.
 */
public final class CassandraDBMUtil {

  private CassandraDBMUtil() {}

  /**
   * Injects a DBM trace comment into the CQL query string if DBM propagation is enabled. Sets the
   * {@code _dd.dbm_trace_injected} tag on the span when injection occurs.
   *
   * @param span the current agent span
   * @param query the original CQL query string
   * @param hostname the database host (may be null)
   * @param dbName the database/keyspace name (may be null)
   * @return the query string with DBM comment prepended, or the original query if DBM is disabled
   */
  public static String injectComment(AgentSpan span, String query, String hostname, String dbName) {
    if (!Config.get().isDbmCommentInjectionEnabled()) {
      return query;
    }

    if (query == null || query.isEmpty()) {
      return query;
    }

    if (span.forceSamplingDecision() == null) {
      return query;
    }

    String dbService = span.getServiceName();
    String traceParent =
        Config.get().getDbmPropagationMode().equals(DBM_PROPAGATION_MODE_FULL)
            ? W3CTraceParent.from(span)
            : null;

    String commentContent =
        SharedDBCommenter.buildComment(dbService, "cassandra", hostname, dbName, traceParent);
    if (commentContent == null || commentContent.isEmpty()) {
      return query;
    }

    // Check for duplicate injection
    if (SharedDBCommenter.containsTraceComment(query)) {
      return query;
    }

    span.setTag(DBM_TRACE_INJECTED, true);

    // Prepend the DBM comment as a CQL comment
    return "/* " + commentContent + " */ " + query;
  }
}
