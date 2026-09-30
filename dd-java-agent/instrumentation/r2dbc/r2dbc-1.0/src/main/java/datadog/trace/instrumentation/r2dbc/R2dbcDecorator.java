package datadog.trace.instrumentation.r2dbc;

import datadog.trace.api.BaseHash;
import datadog.trace.api.Config;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.DatabaseClientDecorator;
import io.r2dbc.spi.ConnectionFactoryOptions;
import io.r2dbc.spi.Option;
import java.util.Collections;
import java.util.Set;

public class R2dbcDecorator extends DatabaseClientDecorator<ConnectionFactoryOptions> {

  public static final R2dbcDecorator DECORATE = new R2dbcDecorator();

  static final CharSequence R2DBC_QUERY =
      UTF8BytesString.create(SpanNaming.instance().namingSchema().database().operation("r2dbc"));
  static final CharSequence DB_QUERY = UTF8BytesString.create("DB Query");
  private static final CharSequence R2DBC = UTF8BytesString.create("r2dbc");
  private static final String DEFAULT_SERVICE_NAME =
      SpanNaming.instance().namingSchema().database().service("r2dbc");

  public static final boolean INJECT_COMMENT = Config.get().isDbmCommentInjectionEnabled();
  private static final boolean DBM_INJECT_SQL_BASE_HASH = Config.get().isDbmInjectSqlBaseHash();
  private static final boolean PROPAGATE_PROCESS_TAGS =
      Config.get().isExperimentalPropagateProcessTagsEnabled();

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

  /**
   * Reads a string option; {@code null} if unset. No {@code hasOption} check is needed: {@code
   * getValue} returns {@code null} for an absent option, unlike {@code getRequiredValue}, which
   * throws.
   */
  static String stringOption(ConnectionFactoryOptions options, Option<?> option) {
    if (options == null) {
      return null;
    }
    Object value = options.getValue(option);
    return value != null ? value.toString() : null;
  }

  @Override
  protected String dbUser(ConnectionFactoryOptions options) {
    return stringOption(options, ConnectionFactoryOptions.USER);
  }

  @Override
  protected String dbInstance(ConnectionFactoryOptions options) {
    return stringOption(options, ConnectionFactoryOptions.DATABASE);
  }

  @Override
  protected CharSequence dbHostname(ConnectionFactoryOptions options) {
    return stringOption(options, ConnectionFactoryOptions.HOST);
  }

  // Driver names that wrap another driver rather than being a database (e.g.
  // r2dbc:pool:postgresql://...). Their provider resolves the delegate through a nested
  // ConnectionFactories.find, which is instrumented and wrapped with the real driver's options, so
  // the wrapper itself must not be wrapped again (duplicate spans, and a mis-derived db type).
  private static final Set<String> WRAPPER_DRIVERS = Collections.singleton("pool");

  public static boolean isWrapperDriver(ConnectionFactoryOptions options) {
    String driver = stringOption(options, ConnectionFactoryOptions.DRIVER);
    return driver != null && WRAPPER_DRIVERS.contains(driver);
  }

  public String extractDbType(ConnectionFactoryOptions options) {
    String driver = stringOption(options, ConnectionFactoryOptions.DRIVER);
    return driver != null ? driver : "r2dbc";
  }

  /** Exposes the protected {@link #processDatabaseType} for use by the listener. */
  public void applyDatabaseType(AgentSpan span, String dbType) {
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

  /** Adds the base hash used in the DBM comment to the span for backend correlation. */
  public void withBaseHash(AgentSpan span) {
    if (INJECT_COMMENT && DBM_INJECT_SQL_BASE_HASH && PROPAGATE_PROCESS_TAGS) {
      String baseHash = BaseHash.getBaseHashStr();
      if (baseHash != null) {
        span.setTag(Tags.BASE_HASH, baseHash);
      }
    }
  }

  @Override
  protected void postProcessServiceAndOperationName(AgentSpan span, NamingEntry namingEntry) {
    if (namingEntry.getService() != null) {
      span.setServiceName(namingEntry.getService(), component());
    }
    span.setOperationName(namingEntry.getOperation());
  }
}
