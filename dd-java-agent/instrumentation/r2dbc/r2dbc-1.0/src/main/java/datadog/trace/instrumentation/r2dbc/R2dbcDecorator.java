package datadog.trace.instrumentation.r2dbc;

import datadog.trace.api.Config;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.DBTypeProcessingDatabaseClientDecorator;

public class R2dbcDecorator extends DBTypeProcessingDatabaseClientDecorator<Void> {
  public static final R2dbcDecorator DECORATE = new R2dbcDecorator();

  private static final String R2DBC = "r2dbc";
  public static final CharSequence R2DBC_QUERY =
      UTF8BytesString.create(SpanNaming.instance().namingSchema().database().operation(R2DBC));
  public static final CharSequence R2DBC_BATCH = UTF8BytesString.create("r2dbc.batch");
  private static final String SERVICE_NAME =
      SpanNaming.instance().namingSchema().database().service(R2DBC);
  private static final CharSequence COMPONENT_NAME = UTF8BytesString.create(R2DBC);

  public static final String DBM_PROPAGATION_MODE = Config.get().getDbmPropagationMode();

  public static final boolean INJECT_COMMENT =
      DBM_PROPAGATION_MODE.equals(Config.DBM_PROPAGATION_MODE_FULL)
          || DBM_PROPAGATION_MODE.equals(Config.DBM_PROPAGATION_MODE_STATIC)
          || DBM_PROPAGATION_MODE.equals(Config.DBM_PROPAGATION_MODE_DYNAMIC_SERVICE);

  public static final boolean INJECT_TRACE_CONTEXT =
      DBM_PROPAGATION_MODE.equals(Config.DBM_PROPAGATION_MODE_FULL);

  @Override
  protected String[] instrumentationNames() {
    return new String[] {R2DBC};
  }

  @Override
  protected String service() {
    return SERVICE_NAME;
  }

  @Override
  protected CharSequence component() {
    return COMPONENT_NAME;
  }

  @Override
  protected CharSequence spanType() {
    return InternalSpanTypes.SQL;
  }

  @Override
  protected String dbType() {
    return R2DBC;
  }

  @Override
  protected String dbUser(Void connection) {
    return null;
  }

  @Override
  protected String dbInstance(Void connection) {
    return null;
  }

  @Override
  protected CharSequence dbHostname(Void connection) {
    return null;
  }

  /**
   * Apply connection metadata tags to the span for peer service computation. Overrides db.type with
   * the actual database product name when available, and sets peer.hostname and db.instance which
   * feed into PeerServiceCalculator.
   */
  public void onConnection(AgentSpan span, R2dbcConnectionInfo info) {
    if (info != null) {
      if (info.getDbType() != null) {
        processDatabaseType(span, info.getDbType());
      }
      if (info.getDbInstance() != null) {
        onInstance(span, info.getDbInstance());
      }
      if (info.getDbUser() != null) {
        span.setTag(Tags.DB_USER, info.getDbUser());
      }
      if (info.getDbHostname() != null) {
        span.setTag(Tags.PEER_HOSTNAME, info.getDbHostname());
      }
    }
  }

  /** Extracts the first SQL keyword (SELECT, INSERT, UPDATE, DELETE, etc.) from a SQL string. */
  public static String extractDbOperation(String sql) {
    if (sql == null || sql.isEmpty()) {
      return null;
    }
    // Skip leading whitespace and SQL comments (e.g., /* DBM comment */)
    int start = 0;
    int len = sql.length();
    while (start < len) {
      // Skip whitespace
      if (Character.isWhitespace(sql.charAt(start))) {
        start++;
        continue;
      }
      // Skip block comments /* ... */
      if (start + 1 < len && sql.charAt(start) == '/' && sql.charAt(start + 1) == '*') {
        int endComment = sql.indexOf("*/", start + 2);
        if (endComment == -1) {
          return null;
        }
        start = endComment + 2;
        continue;
      }
      break;
    }
    // Find the end of the first word
    int end = start;
    while (end < len && !Character.isWhitespace(sql.charAt(end))) {
      end++;
    }
    if (start == end) {
      return null;
    }
    return sql.substring(start, end).toUpperCase();
  }
}
