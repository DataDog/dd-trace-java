package datadog.trace.instrumentation.r2dbc;

import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.DatabaseClientDecorator;
import datadog.trace.bootstrap.instrumentation.jdbc.DBQueryInfo;

public class R2dbcDecorator extends DatabaseClientDecorator<R2dbcConnectionInfo> {

  public static final R2dbcDecorator DECORATE = new R2dbcDecorator();

  public static final CharSequence R2DBC_SPI = UTF8BytesString.create("r2dbc-spi");
  public static final CharSequence DATABASE_QUERY = UTF8BytesString.create("database.query");
  public static final CharSequence R2DBC_BATCH = UTF8BytesString.create("r2dbc.batch");
  private static final UTF8BytesString DB_QUERY = UTF8BytesString.create("DB Query");

  private static final String DEFAULT_SERVICE_NAME =
      SpanNaming.instance().namingSchema().database().service("r2dbc");

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"r2dbc", "r2dbc-spi"};
  }

  @Override
  protected String service() {
    return DEFAULT_SERVICE_NAME;
  }

  @Override
  protected CharSequence component() {
    return R2DBC_SPI;
  }

  @Override
  protected CharSequence spanType() {
    return InternalSpanTypes.SQL;
  }

  @Override
  protected String dbType() {
    return "r2dbc";
  }

  @Override
  protected String dbUser(R2dbcConnectionInfo info) {
    return info != null ? info.getUser() : null;
  }

  @Override
  protected String dbInstance(R2dbcConnectionInfo info) {
    return info != null ? info.getDbName() : null;
  }

  @Override
  protected CharSequence dbHostname(R2dbcConnectionInfo info) {
    return info != null ? info.getHost() : null;
  }

  public void onDatabase(AgentSpan span, R2dbcConnectionInfo info) {
    String type = (info != null && info.getDbType() != null) ? info.getDbType() : dbType();
    processDatabaseType(span, type);
    if (info != null) {
      onConnection(span, info);
      if (info.getPort() != null && info.getPort() > 0) {
        span.setTag(Tags.PEER_PORT, info.getPort());
      }
    }
  }

  public void onStatement(AgentSpan span, String sql) {
    if (sql != null && !sql.isEmpty()) {
      onRawStatement(span, sql);
      DBQueryInfo info = DBQueryInfo.ofStatement(sql);
      span.setResourceName(info.getSql());
      span.setTag(Tags.DB_OPERATION, info.getOperation());
    } else {
      span.setResourceName(DB_QUERY);
    }
  }

  @Override
  protected void postProcessServiceAndOperationName(
      AgentSpan span, DatabaseClientDecorator.NamingEntry namingEntry) {
    if (namingEntry.getService() != null) {
      span.setServiceName(namingEntry.getService(), component());
    }
    span.setOperationName(namingEntry.getOperation());
  }
}
