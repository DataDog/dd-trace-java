package datadog.trace.bootstrap.instrumentation.decorator;

import static datadog.trace.bootstrap.instrumentation.api.Tags.DB_TYPE;

import datadog.trace.api.Config;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.api.naming.NamingSchema;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.util.function.Function;

public abstract class DatabaseClientDecorator<CONNECTION> extends ClientDecorator {

  private static class NamingEntry {
    private final String service;
    private final CharSequence operation;

    private NamingEntry(String service, CharSequence operation) {
      this.service = service;
      this.operation = operation;
    }

    public String getService() {
      return service;
    }

    public CharSequence getOperation() {
      return operation;
    }
  }

  // The total number of entries in the cache will normally be less than 4, since
  // most applications only have one or two DBs, and "jdbc" itself is also used as
  // one DB_TYPE, but set the cache size to 16 to help avoid collisions.
  private static final DDCache<String, NamingEntry> CACHE = DDCaches.newFixedSizeCache(16);
  private static final Function<String, NamingEntry> APPEND_OPERATION =
      dbType -> {
        final NamingSchema.ForDatabase schema = SpanNaming.instance().namingSchema().database();
        return new NamingEntry(
            schema.service(Config.get().getServiceName(), dbType),
            UTF8BytesString.create(schema.operation(dbType)));
      };

  protected abstract String dbType();

  protected abstract String dbUser(CONNECTION connection);

  protected abstract String dbInstance(CONNECTION connection);

  protected abstract CharSequence dbHostname(CONNECTION connection);

  /**
   * This should be called when the connection is being used, not when it's created.
   *
   * @param span
   * @param connection
   * @return
   */
  public AgentSpan onConnection(final AgentSpan span, final CONNECTION connection) {
    if (connection != null) {
      span.setTag(Tags.DB_USER, dbUser(connection));
      final String instanceName = dbInstance(connection);
      span.setTag(Tags.DB_INSTANCE, instanceName);

      String serviceName = dbClientService(instanceName);
      if (null != serviceName) {
        span.setServiceName(serviceName);
      }

      CharSequence hostName = dbHostname(connection);
      if (hostName != null) {
        span.setTag(Tags.PEER_HOSTNAME, hostName);
      }
    }
    return span;
  }

  public String dbService(final String dbType, final String instanceName) {
    if (instanceName != null && Config.get().isDbClientSplitByInstance()) {
      return dbClientService(instanceName);
    }
    final NamingEntry entry = CACHE.computeIfAbsent(dbType, APPEND_OPERATION);
    return entry.getService();
  }

  public String dbClientService(final String instanceName) {
    String service = null;
    if (instanceName != null && Config.get().isDbClientSplitByInstance()) {
      service =
          Config.get().isDbClientSplitByInstanceTypeSuffix()
              ? instanceName + "-" + dbType()
              : instanceName;
    }
    return service;
  }

  public AgentSpan onStatement(final AgentSpan span, final CharSequence statement) {
    span.setResourceName(statement);
    return span;
  }

  protected void processDatabaseType(AgentSpan span, String dbType) {
    span.setTag(DB_TYPE, dbType);
    postProcessServiceAndOperationName(span, dbType);
  }

  protected void postProcessServiceAndOperationName(AgentSpan span, String dbType) {
    final NamingEntry namingEntry = CACHE.computeIfAbsent(dbType, APPEND_OPERATION);
    span.setServiceName(namingEntry.getService());
    span.setOperationName(namingEntry.getOperation());
  }
}
