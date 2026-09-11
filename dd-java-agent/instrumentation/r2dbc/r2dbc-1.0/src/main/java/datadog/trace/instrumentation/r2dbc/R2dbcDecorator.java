package datadog.trace.instrumentation.r2dbc;

import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.DatabaseClientDecorator;
import io.r2dbc.spi.ConnectionFactoryOptions;

public class R2dbcDecorator extends DatabaseClientDecorator<ConnectionFactoryOptions> {

  public static final R2dbcDecorator DECORATE = new R2dbcDecorator();

  static final CharSequence R2DBC_QUERY =
      UTF8BytesString.create(SpanNaming.instance().namingSchema().database().operation("r2dbc"));
  private static final CharSequence R2DBC = UTF8BytesString.create("r2dbc");
  private static final String DEFAULT_SERVICE_NAME =
      SpanNaming.instance().namingSchema().database().service("r2dbc");

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"r2dbc"};
  }

  @Override
  protected String service() {
    return DEFAULT_SERVICE_NAME;
  }

  @Override
  protected CharSequence component() {
    return R2DBC;
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
  protected String dbUser(ConnectionFactoryOptions options) {
    if (options == null) {
      return null;
    }
    Object user = options.getValue(ConnectionFactoryOptions.USER);
    return user != null ? user.toString() : null;
  }

  @Override
  protected String dbInstance(ConnectionFactoryOptions options) {
    if (options == null) {
      return null;
    }
    Object database = options.getValue(ConnectionFactoryOptions.DATABASE);
    return database != null ? database.toString() : null;
  }

  @Override
  protected CharSequence dbHostname(ConnectionFactoryOptions options) {
    if (options == null) {
      return null;
    }
    Object host = options.getValue(ConnectionFactoryOptions.HOST);
    return host != null ? host.toString() : null;
  }

  public String extractDbType(ConnectionFactoryOptions options) {
    if (options != null && options.hasOption(ConnectionFactoryOptions.DRIVER)) {
      Object driver = options.getValue(ConnectionFactoryOptions.DRIVER);
      if (driver != null) {
        return driver.toString();
      }
    }
    return "r2dbc";
  }

  /** Exposes the protected {@link #processDatabaseType} for use by the listener. */
  public void applyDatabaseType(
      datadog.trace.bootstrap.instrumentation.api.AgentSpan span, String dbType) {
    processDatabaseType(span, dbType);
  }

  /**
   * Returns the database service name derived from the connection options. Used for DBM SQL comment
   * injection.
   */
  public String getDbService(ConnectionFactoryOptions options) {
    String dbType = extractDbType(options);
    String instanceName = dbInstance(options);
    return dbService(dbType, instanceName);
  }

  @Override
  protected void postProcessServiceAndOperationName(
      datadog.trace.bootstrap.instrumentation.api.AgentSpan span, NamingEntry namingEntry) {
    if (namingEntry.getService() != null) {
      span.setServiceName(namingEntry.getService(), component());
    }
    span.setOperationName(namingEntry.getOperation());
  }
}
