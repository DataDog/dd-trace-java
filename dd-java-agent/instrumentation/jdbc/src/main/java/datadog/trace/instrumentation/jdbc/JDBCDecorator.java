package datadog.trace.instrumentation.jdbc;

import static datadog.trace.bootstrap.instrumentation.api.Tags.DB_OPERATION;

import datadog.trace.api.Config;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.naming.SpanNaming;
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
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JDBCDecorator extends DatabaseClientDecorator<DBInfo> {

  private static final Logger log = LoggerFactory.getLogger(JDBCDecorator.class);

  public static final JDBCDecorator DECORATE = new JDBCDecorator();
  public static final CharSequence JAVA_JDBC = UTF8BytesString.create("java-jdbc");
  public static final CharSequence DATABASE_QUERY = UTF8BytesString.create("database.query");
  private static final UTF8BytesString DB_QUERY = UTF8BytesString.create("DB Query");
  private static final UTF8BytesString JDBC_STATEMENT =
      UTF8BytesString.create("java-jdbc-statement");
  private static final UTF8BytesString JDBC_PREPARED_STATEMENT =
      UTF8BytesString.create("java-jdbc-prepared_statement");
  private static final String DEFAULT_SERVICE_NAME =
      SpanNaming.instance().namingSchema().database().service("jdbc");
  public static final String DBM_PROPAGATION_MODE_STATIC = "service";
  public static final String DBM_PROPAGATION_MODE_FULL = "full";

  public static final String DBM_PROPAGATION_MODE = Config.get().getDBMPropagationMode();
  public static final boolean INJECT_COMMENT =
      DBM_PROPAGATION_MODE.equals(DBM_PROPAGATION_MODE_FULL)
          || DBM_PROPAGATION_MODE.equals(DBM_PROPAGATION_MODE_STATIC);
  private static final boolean INJECT_TRACE_CONTEXT =
      DBM_PROPAGATION_MODE.equals(DBM_PROPAGATION_MODE_FULL);

  private volatile boolean warnedAboutDBMPropagationMode = false; // to log a warning only once

  public static void logMissingQueryInfo(Statement statement) throws SQLException {
    if (log.isDebugEnabled()) {
      log.debug(
          "No query info in {} with {}",
          statement.getClass(),
          statement.getConnection().getClass());
    }
  }

  public static void logQueryInfoInjection(
      Connection connection, Statement statement, DBQueryInfo info) {
    if (log.isDebugEnabled()) {
      log.debug(
          "injected {} into {} from {}",
          info.getSql(),
          statement.getClass(),
          connection.getClass());
    }
  }

  public static void logSQLException(SQLException ex) {
    if (log.isDebugEnabled()) {
      log.debug("JDBC instrumentation error", ex);
    }
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"jdbc"};
  }

  @Override
  protected String service() {
    return DEFAULT_SERVICE_NAME; // Overridden by onConnection
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

  public AgentSpan onConnection(final AgentSpan span, DBInfo dbInfo) {
    if (dbInfo != null) {
      processDatabaseType(span, dbInfo.getType());
    }
    return super.onConnection(span, dbInfo);
  }

  public static DBInfo parseDBInfo(
      final Connection connection, ContextStore<Connection, DBInfo> contextStore) {
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
        // first look for injected DBInfo in wrapped delegates
        Connection conn = connection;
        Set<Connection> connections = new HashSet<>();
        connections.add(conn);
        try {
          while (dbInfo == null) {
            Connection delegate = conn.unwrap(Connection.class);
            if (delegate == null || !connections.add(delegate)) {
              // cycle detected, stop looking
              break;
            }
            dbInfo = contextStore.get(delegate);
            conn = delegate;
          }
        } catch (Throwable ignore) {
        }
        if (dbInfo == null) {
          // couldn't find DBInfo anywhere, so fall back to default
          dbInfo = parseDBInfoFromConnection(connection);
        }
        // store the DBInfo on the outermost connection instance to avoid future searches
        contextStore.put(connection, dbInfo);
      }
    }
    return dbInfo;
  }

  public String getDbService(final DBInfo dbInfo) {
    String dbService = null;
    if (null != dbInfo) {
      dbService = dbService(dbInfo.getType(), dbInstance(dbInfo));
    }
    return dbService;
  }

  public static DBInfo parseDBInfoFromConnection(final Connection connection) {
    DBInfo dbInfo;
    try {
      final DatabaseMetaData metaData = connection.getMetaData();
      final String url = metaData.getURL();
      if (url != null) {
        try {
          dbInfo = JDBCConnectionUrlParser.extractDBInfo(url, connection.getClientInfo());
        } catch (final Throwable ex) {
          // getClientInfo is likely not allowed.
          dbInfo = JDBCConnectionUrlParser.extractDBInfo(url, null);
        }
      } else {
        dbInfo = DBInfo.DEFAULT;
      }
    } catch (final SQLException se) {
      dbInfo = DBInfo.DEFAULT;
    }
    return dbInfo;
  }

  public AgentSpan onStatement(AgentSpan span, DBQueryInfo dbQueryInfo) {
    return withQueryInfo(span, dbQueryInfo, JDBC_STATEMENT);
  }

  public AgentSpan onPreparedStatement(AgentSpan span, DBQueryInfo dbQueryInfo) {
    return withQueryInfo(span, dbQueryInfo, JDBC_PREPARED_STATEMENT);
  }

  private AgentSpan withQueryInfo(AgentSpan span, DBQueryInfo info, CharSequence component) {
    if (null != info) {
      span.setResourceName(info.getSql());
      span.setTag(DB_OPERATION, info.getOperation());
    } else {
      span.setResourceName(DB_QUERY);
    }
    return span.setTag(Tags.COMPONENT, component);
  }

  public String traceParent(AgentSpan span, int samplingPriority) {
    StringBuilder sb = new StringBuilder(55);
    sb.append("00-");
    sb.append(span.getTraceId().toHexString());
    sb.append("-");
    sb.append(DDSpanId.toHexStringPadded(span.getSpanId()));
    sb.append(samplingPriority > 0 ? "-01" : "-00");
    return sb.toString();
  }

  @Override
  protected void postProcessServiceAndOperationName(
      AgentSpan span, DatabaseClientDecorator.NamingEntry namingEntry) {
    if (namingEntry.getService() != null) {
      span.setServiceName(namingEntry.getService());
    }
    span.setOperationName(namingEntry.getOperation());
  }

  public boolean shouldInjectTraceContext(DBInfo dbInfo) {
    if (INJECT_TRACE_CONTEXT && !dbInfo.getFullPropagationSupport()) {
      if (!warnedAboutDBMPropagationMode) {
        log.warn(
            "Using DBM_PROPAGATION_MODE in 'full' mode is not supported for {}. "
                + "See https://docs.datadoghq.com/database_monitoring/connect_dbm_and_apm/ for more info.",
            dbInfo.getType());
        warnedAboutDBMPropagationMode = true;
      }
      return false;
    }
    return INJECT_TRACE_CONTEXT;
  }
}
