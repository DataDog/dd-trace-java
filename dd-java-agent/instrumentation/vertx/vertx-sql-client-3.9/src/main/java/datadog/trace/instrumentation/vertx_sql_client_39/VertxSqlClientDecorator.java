package datadog.trace.instrumentation.vertx_sql_client_39;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.Tags.DB_OPERATION;

import datadog.trace.api.Pair;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.DatabaseClientDecorator;
import datadog.trace.bootstrap.instrumentation.jdbc.DBInfo;
import datadog.trace.bootstrap.instrumentation.jdbc.DBQueryInfo;

public class VertxSqlClientDecorator extends DatabaseClientDecorator<DBInfo> {

  public static final VertxSqlClientDecorator DECORATE = new VertxSqlClientDecorator();

  private static final CharSequence VERTX_SQL = UTF8BytesString.create("vertx-sql");
  private static final CharSequence DATABASE_QUERY = UTF8BytesString.create("database.query");
  private static final UTF8BytesString DB_QUERY = UTF8BytesString.create("DB Query");
  private static final UTF8BytesString VERTX_STATEMENT =
      UTF8BytesString.create("vertx-sql-statement");
  private static final UTF8BytesString VERTX_PREPARED_STATEMENT =
      UTF8BytesString.create("vertx-sql-prepared_statement");
  private static final String DEFAULT_SERVICE_NAME =
      SpanNaming.instance().namingSchema().database().service("vertx-sql");

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"vertx", "vertx-sql-client"};
  }

  @Override
  protected String service() {
    return DEFAULT_SERVICE_NAME; // Overridden by onConnection
  }

  @Override
  protected CharSequence component() {
    return VERTX_SQL; // Overridden by onStatement and onPreparedStatement
  }

  @Override
  protected CharSequence spanType() {
    return InternalSpanTypes.SQL;
  }

  @Override
  protected String dbType() {
    return "vertx-sql";
  }

  @Override
  protected String dbUser(final DBInfo info) {
    return info.getUser();
  }

  @Override
  protected String dbInstance(final DBInfo info) {
    if (info.getInstance() != null) {
      return info.getInstance();
    } else {
      return info.getDb();
    }
  }

  @Override
  protected String dbHostname(final DBInfo info) {
    return info.getHost();
  }

  public <T> AgentSpan startAndDecorateSpanForStatement(
      T query, ContextStore<T, Pair> contextStore, boolean prepared) {
    CharSequence component = prepared ? VERTX_PREPARED_STATEMENT : VERTX_STATEMENT;
    AgentSpan span = startSpan(DATABASE_QUERY);
    if (null == span) {
      return null;
    }
    afterStart(span);

    DBInfo dbInfo = null;
    DBQueryInfo dbQueryInfo = null;
    Pair<DBInfo, DBQueryInfo> queryInfo = contextStore.get(query);
    if (queryInfo != null) {
      dbInfo = queryInfo.getLeft();
      dbQueryInfo = queryInfo.getRight();
    }

    if (dbInfo != null) {
      processDatabaseType(span, dbInfo.getType());
    }
    super.onConnection(span, dbInfo);
    if (null != dbQueryInfo) {
      span.setResourceName(dbQueryInfo.getSql());
      span.setTag(DB_OPERATION, dbQueryInfo.getOperation());
    } else {
      span.setResourceName(DB_QUERY);
    }
    span.setTag(Tags.COMPONENT, component);
    span.context().setIntegrationName(component);
    return span;
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
