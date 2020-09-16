package datadog.trace.instrumentation.jdbc;

import datadog.trace.api.DDSpanTypes;
import datadog.trace.api.DDTags;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.DatabaseClientDecorator;
import datadog.trace.bootstrap.instrumentation.jdbc.DBInfo;
import datadog.trace.bootstrap.instrumentation.jdbc.JDBCConnectionUrlParser;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public class JDBCDecorator extends DatabaseClientDecorator<DBInfo> {

  public static final JDBCDecorator DECORATE = new JDBCDecorator();
  public static final CharSequence DATABASE_QUERY =
      UTF8BytesString.createConstant("database.query");
  private static final UTF8BytesString DB_QUERY = UTF8BytesString.createConstant("DB Query");
  private static final UTF8BytesString JDBC_STATEMENT =
      UTF8BytesString.createConstant("java-jdbc-statement");
  private static final UTF8BytesString JDBC_PREPARED_STATEMENT =
      UTF8BytesString.createConstant("java-jdbc-prepared_statement");

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"jdbc"};
  }

  @Override
  protected String service() {
    return "jdbc"; // Overridden by onConnection
  }

  @Override
  protected String component() {
    return "java-jdbc"; // Overridden by onStatement and onPreparedStatement
  }

  @Override
  protected String spanType() {
    return DDSpanTypes.SQL;
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

  public AgentSpan onConnection(final AgentSpan span, final Connection connection) {
    DBInfo dbInfo = JDBCMaps.connectionInfo.get(connection);
    /**
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
        JDBCMaps.connectionInfo.put(connection, dbInfo);
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
    span.setTag(DDTags.RESOURCE_NAME, resourceName);
    span.setTag(Tags.COMPONENT, JDBC_STATEMENT);
    return super.onStatement(span, statement);
  }

  public AgentSpan onPreparedStatement(final AgentSpan span, final PreparedStatement statement) {
    final UTF8BytesString sql = JDBCMaps.preparedStatements.get(statement);
    final UTF8BytesString resourceName = sql == null ? DB_QUERY : sql;
    span.setTag(DDTags.RESOURCE_NAME, resourceName);
    span.setTag(Tags.COMPONENT, JDBC_PREPARED_STATEMENT);
    return super.onStatement(span, sql);
  }
}
