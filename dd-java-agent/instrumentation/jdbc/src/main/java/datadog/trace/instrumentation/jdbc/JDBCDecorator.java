package datadog.trace.instrumentation.jdbc;

import static datadog.trace.bootstrap.instrumentation.api.Tags.DB_OPERATION;
import static datadog.trace.bootstrap.instrumentation.jdbc.DBQueryInfo.extractOperation;

import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.DatabaseClientDecorator;
import datadog.trace.bootstrap.instrumentation.jdbc.DBInfo;
import datadog.trace.bootstrap.instrumentation.jdbc.DBQueryInfo;
import datadog.trace.bootstrap.instrumentation.jdbc.JDBCConnectionUrlParser;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;

public class JDBCDecorator extends DatabaseClientDecorator<DBInfo> {

  // use a fixed size cache to avoid creating background cleanup work
  public static final DDCache<String, UTF8BytesString> PREPARED_STATEMENTS_SQL =
      DDCaches.newFixedSizeCache(256);

  public static final JDBCDecorator DECORATE = new JDBCDecorator();
  public static final CharSequence JAVA_JDBC = UTF8BytesString.create("java-jdbc");
  public static final CharSequence DATABASE_QUERY = UTF8BytesString.create("database.query");
  private static final UTF8BytesString DB_QUERY = UTF8BytesString.create("DB Query");
  private static final UTF8BytesString JDBC_STATEMENT =
      UTF8BytesString.create("java-jdbc-statement");
  private static final UTF8BytesString JDBC_PREPARED_STATEMENT =
      UTF8BytesString.create("java-jdbc-prepared_statement");

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"jdbc"};
  }

  @Override
  protected String service() {
    return "jdbc"; // Overridden by onConnection
  }

  @Override
  protected CharSequence component() {
    return JAVA_JDBC; // Overridden by onStatement and onPreparedStatement
  }

  @Override
  protected CharSequence spanType() {
    return InternalSpanTypes.SQL;
  }

  @Override
  protected String dbType() {
    return "jdbc";
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

  public AgentSpan onConnection(
      final AgentSpan span,
      final Connection connection,
      ContextStore<Connection, DBInfo> contextStore) {
    DBInfo dbInfo = contextStore.get(connection);
    /*
     * Logic to get the DBInfo from a JDBC Connection, if the connection was not created via
     * Driver.connect, or it has never seen before, the connectionInfo map will return null and will
     * attempt to extract DBInfo from the connection. If the DBInfo can't be extracted, then the
     * connection will be stored with the DEFAULT DBInfo as the value in the connectionInfo map to
     * avoid retry overhead.
     */
    {
      if (dbInfo == null) {
        try {
          final DatabaseMetaData metaData = connection.getMetaData();
          final String url = metaData.getURL();
          if (url != null) {
            try {
              dbInfo = JDBCConnectionUrlParser.parse(url, connection.getClientInfo());
            } catch (final Throwable ex) {
              // getClientInfo is likely not allowed.
              dbInfo = JDBCConnectionUrlParser.parse(url, null);
            }
          } else {
            dbInfo = DBInfo.DEFAULT;
          }
        } catch (final SQLException se) {
          dbInfo = DBInfo.DEFAULT;
        }
        contextStore.put(connection, dbInfo);
      }
    }

    if (dbInfo != null) {
      processDatabaseType(span, dbInfo.getType());
    }
    return super.onConnection(span, dbInfo);
  }

  @Override
  public AgentSpan onStatement(final AgentSpan span, final CharSequence statement) {
    final CharSequence resourceName = statement == null ? DB_QUERY : statement;
    span.setTag(DB_OPERATION, extractOperation(statement));
    span.setResourceName(resourceName);
    span.setTag(Tags.COMPONENT, JDBC_STATEMENT);
    return span;
  }

  public AgentSpan onPreparedStatement(final AgentSpan span, DBQueryInfo dbQueryInfo) {
    if (null != dbQueryInfo) {
      span.setResourceName(dbQueryInfo.getSql());
      span.setTag(DB_OPERATION, dbQueryInfo.getOperation());
    } else {
      span.setResourceName(DB_QUERY);
    }
    span.setTag(Tags.COMPONENT, JDBC_PREPARED_STATEMENT);
    return span;
  }
}
