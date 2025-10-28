package datadog.trace.instrumentation.jdbc;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.DBM_TRACE_INJECTED;
import static datadog.trace.bootstrap.instrumentation.api.InstrumentationTags.INSTRUMENTATION_TIME_MS;
import static datadog.trace.bootstrap.instrumentation.api.Tags.*;

import datadog.trace.api.Config;
import datadog.trace.api.DDSpanId;
import datadog.trace.api.DDTraceId;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.ContextStore;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.DatabaseClientDecorator;
import datadog.trace.bootstrap.instrumentation.jdbc.DBInfo;
import datadog.trace.bootstrap.instrumentation.jdbc.DBQueryInfo;
import datadog.trace.bootstrap.instrumentation.jdbc.JDBCConnectionUrlParser;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
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

  public static final String DD_INSTRUMENTATION_PREFIX = "_DD_";

  public static final String DBM_PROPAGATION_MODE = Config.get().getDbmPropagationMode();
  public static final boolean INJECT_COMMENT =
      DBM_PROPAGATION_MODE.equals(DBM_PROPAGATION_MODE_FULL)
          || DBM_PROPAGATION_MODE.equals(DBM_PROPAGATION_MODE_STATIC);
  private static final boolean INJECT_TRACE_CONTEXT =
      DBM_PROPAGATION_MODE.equals(DBM_PROPAGATION_MODE_FULL);
  public static final boolean DBM_TRACE_PREPARED_STATEMENTS =
      Config.get().isDbmTracePreparedStatements();
  public static final boolean DBM_ALWAYS_APPEND_SQL_COMMENT =
      Config.get().isDbmAlwaysAppendSqlComment();

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

  private void setTagIfPresent(final AgentSpan span, final String key, final String value) {
    if (value != null && !value.isEmpty()) {
      span.setTag(key, value);
    }
  }

  public AgentSpan onConnection(final AgentSpan span, DBInfo dbInfo) {
    if (dbInfo != null) {
      processDatabaseType(span, dbInfo.getType());

      setTagIfPresent(span, DB_WAREHOUSE, dbInfo.getWarehouse());
      setTagIfPresent(span, DB_SCHEMA, dbInfo.getSchema());
      setTagIfPresent(span, DB_POOL_NAME, dbInfo.getPoolName());
    }
    return super.onConnection(span, dbInfo);
  }

  public static DBInfo parseDBInfo(
      final Connection connection, ContextStore<Connection, DBInfo> contextStore) {
    if (connection == null) {
      return DBInfo.DEFAULT;
    }
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
    if (connection == null) {
      // we can log here, but it risks to be too verbose
      return DBInfo.DEFAULT;
    }
    DBInfo dbInfo;
    try {
      final DatabaseMetaData metaData = connection.getMetaData();
      final String url;
      if (metaData != null && (url = metaData.getURL()) != null) {
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

  public AgentSpan onStatement(AgentSpan span, final String statement) {
    onRawStatement(span, statement);
    DBQueryInfo dbQueryInfo = DBQueryInfo.ofStatement(statement);
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
    span.context().setIntegrationName(component);
    return span.setTag(Tags.COMPONENT, component);
  }

  public String traceParent(AgentSpan span, int samplingPriority) {
    StringBuilder sb = new StringBuilder(55);
    sb.append("00-");
    sb.append(span.getTraceId().toHexString());
    sb.append('-');
    sb.append(DDSpanId.toHexStringPadded(span.getSpanId()));
    sb.append(samplingPriority > 0 ? "-01" : "-00");
    return sb.toString();
  }

  public boolean isOracle(final DBInfo dbInfo) {
    return "oracle".equals(dbInfo.getType());
  }

  public boolean isPostgres(final DBInfo dbInfo) {
    return dbInfo.getType().startsWith("postgres");
  }

  public boolean isSqlServer(final DBInfo dbInfo) {
    return "sqlserver".equals(dbInfo.getType());
  }

  /**
   * Executes `connection.setClientInfo("OCSID.ACTION", traceContext)` statement on the Oracle DB to
   * set the trace parent in `v$session.action`. This is used because it isn't possible to propagate
   * trace parent with the comment.
   *
   * @param span The span of the instrumented statement
   * @param connection The same connection as the one that will be used for the actual statement
   */
  public void setAction(AgentSpan span, Connection connection) {
    try {

      Integer priority = span.forceSamplingDecision();
      if (priority == null) {
        return;
      }
      final String traceContext = DD_INSTRUMENTATION_PREFIX + DECORATE.traceParent(span, priority);

      connection.setClientInfo("OCSID.ACTION", traceContext);

      span.setTag("_dd.dbm_trace_injected", true);
    } catch (Throwable e) {
      log.debug(
          "Failed to set extra DBM data in application_name for trace {}. "
              + "To disable this behavior, set trace_prepared_statements to 'false'. "
              + "See https://docs.datadoghq.com/database_monitoring/connect_dbm_and_apm/ for more info. {}",
          span.getTraceId().toHexString(),
          e);
      DECORATE.onError(span, e);
    }
  }

  /**
   * Executes a `SET CONTEXT_INFO` statement on the DB with the active trace ID and the given span
   * ID. This context will be "attached" to future queries on the same connection. See <a
   * href="https://learn.microsoft.com/fr-fr/sql/t-sql/functions/context-info-transact-sql">MSSQL
   * doc</a>. This is to be used where injecting trace and span in the comments with {@link
   * SQLCommenter#inject} is not possible or convenient.
   *
   * <p>Upsides: still "visible" in sub-queries, does not bust caches based on full query text
   * Downsides: takes time.
   *
   * @param connection The same connection as the one that will be used for the actual statement
   * @param dbInfo dbInfo of the instrumented database
   * @return spanID pre-created spanID
   */
  public long setContextInfo(Connection connection, DBInfo dbInfo) {
    final byte VERSION = 0;
    final long spanID = Config.get().getIdGenerationStrategy().generateSpanId();
    // potentially get build span like here
    AgentSpan instrumentationSpan =
        AgentTracer.get().buildSpan("set context_info").withTag("dd.instrumentation", true).start();
    DECORATE.afterStart(instrumentationSpan);
    DECORATE.onConnection(instrumentationSpan, dbInfo);
    try (AgentScope scope = activateSpan(instrumentationSpan)) {
      final byte samplingDecision =
          (byte) (instrumentationSpan.forceSamplingDecision() > 0 ? 1 : 0);
      final byte versionAndSamplingDecision =
          (byte) ((VERSION << 4) & 0b11110000 | samplingDecision & 0b00000001);

      ByteBuffer byteBuffer = ByteBuffer.allocate(1 + 3 * Long.BYTES);
      byteBuffer.order(ByteOrder.BIG_ENDIAN);

      byteBuffer.put(versionAndSamplingDecision);
      byteBuffer.putLong(spanID);
      final DDTraceId traceId = instrumentationSpan.getTraceId();
      byteBuffer.putLong(traceId.toHighOrderLong());
      byteBuffer.putLong(traceId.toLong());
      final byte[] contextInfo = byteBuffer.array();
      String instrumentationSql = "set context_info ?";
      try (PreparedStatement instrumentationStatement =
          connection.prepareStatement(instrumentationSql)) {
        instrumentationStatement.setBytes(1, contextInfo);
        DECORATE.onStatement(instrumentationSpan, instrumentationSql);
        instrumentationStatement.execute();
      } catch (SQLException e) {
        throw e;
      }
    } catch (Exception e) {
      log.debug(
          "Failed to set extra DBM data in context info for trace {}. "
              + "To disable this behavior, set DBM_PROPAGATION_MODE to 'service' mode. "
              + "See https://docs.datadoghq.com/database_monitoring/connect_dbm_and_apm/ for more info.{}",
          instrumentationSpan.getTraceId().toHexString(),
          e);
      DECORATE.onError(instrumentationSpan, e);
    } finally {
      instrumentationSpan.finish();
    }
    return spanID;
  }

  /**
   * Executes `SET application_name` statement on the Postgres DB to set the trace parent in
   * `pg_stat_activity.application_name`. This is used for prepared statements where it isn't
   * possible to propagate trace parent with the comment. Downside: makes an additional round trip
   * to the database.
   *
   * @param span The span of the instrumented statement
   * @param connection The same connection as the one that will be used for the actual statement
   */
  public void setApplicationName(AgentSpan span, Connection connection) {
    final long startTime = System.currentTimeMillis();
    try {

      Integer priority = span.forceSamplingDecision();
      if (priority == null) {
        return;
      }
      final String traceParent = DECORATE.traceParent(span, priority);
      final String traceContext = "_DD_" + traceParent;

      connection.setClientInfo("ApplicationName", traceContext);
    } catch (Throwable e) {
      if (log.isDebugEnabled()) {
        log.debug(
            "Failed to set extra DBM data in application_name for trace {}. "
                + "To disable this behavior, set trace_prepared_statements to 'false'. "
                + "See https://docs.datadoghq.com/database_monitoring/connect_dbm_and_apm/ for more info.{}",
            span.getTraceId().toHexString(),
            e);
      }
      DECORATE.onError(span, e);
    } finally {
      span.setTag(DBM_TRACE_INJECTED, true);
      final long elapsed = System.currentTimeMillis() - startTime;
      span.setTag(INSTRUMENTATION_TIME_MS, elapsed);
    }
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

  public boolean shouldInjectSQLComment() {
    return Config.get().getDbmPropagationMode().equals(DBM_PROPAGATION_MODE_FULL)
        || Config.get().getDbmPropagationMode().equals(DBM_PROPAGATION_MODE_STATIC);
  }
}
