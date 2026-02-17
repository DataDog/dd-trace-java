package datadog.trace.bootstrap.instrumentation.decorator;

import static datadog.trace.api.gateway.Events.EVENTS;
import static datadog.trace.bootstrap.instrumentation.api.Tags.DB_TYPE;

import datadog.appsec.api.blocking.BlockingException;
import datadog.trace.api.Config;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.api.gateway.BlockResponseFunction;
import datadog.trace.api.gateway.Flow;
import datadog.trace.api.gateway.RequestContext;
import datadog.trace.api.gateway.RequestContextSlot;
import datadog.trace.api.naming.NamingSchema;
import datadog.trace.api.naming.SpanNaming;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;

public abstract class DatabaseClientDecorator<CONNECTION> extends ClientDecorator {
  protected static class NamingEntry {
    private final String service;
    private final CharSequence operation;

    private final String dbType;

    private NamingEntry(String rawDbType) {
      final NamingSchema.ForDatabase schema = SpanNaming.instance().namingSchema().database();
      this.dbType = schema.normalizedName(rawDbType);
      this.service = schema.service(dbType);
      this.operation = UTF8BytesString.create(schema.operation(dbType));
    }

    public String getService() {
      return service;
    }

    public CharSequence getOperation() {
      return operation;
    }

    public String getDbType() {
      return dbType;
    }
  }

  // The total number of entries in the cache will normally be less than 4, since
  // most applications only have one or two DBs, and "jdbc" itself is also used as
  // one DB_TYPE, but set the cache size to 16 to help avoid collisions.
  private static final DDCache<String, NamingEntry> CACHE = DDCaches.newFixedSizeCache(16);

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
      onInstance(span, dbInstance(connection));
      CharSequence hostName = dbHostname(connection);
      if (hostName != null) {
        span.setTag(Tags.PEER_HOSTNAME, hostName);

        if (Config.get().isDbClientSplitByHost()) {
          span.setServiceName(hostName.toString(), component());
        }
      }
    }
    return span;
  }

  protected AgentSpan onInstance(final AgentSpan span, final String dbInstance) {
    if (dbInstance != null) {
      span.setTag(Tags.DB_INSTANCE, dbInstance);
      String serviceName = dbClientService(dbInstance);
      if (null != serviceName) {
        span.setServiceName(serviceName, component());
      }
    }
    return span;
  }

  public String dbService(final String dbType, final String instanceName) {
    if (instanceName != null && Config.get().isDbClientSplitByInstance()) {
      return dbClientService(instanceName);
    }
    final NamingEntry entry = CACHE.computeIfAbsent(dbType, NamingEntry::new);
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

  /**
   * The method used to provide raw sql to prevent SQL-injection attacks SQL query should never be
   * exposed because it may contain sensitive data.
   */
  public void onRawStatement(AgentSpan span, String sql) {
    if (Config.get().isAppSecRaspEnabled() && sql != null && !sql.isEmpty()) {
      BiFunction<RequestContext, String, Flow<Void>> sqlQueryCallback =
          AgentTracer.get()
              .getCallbackProvider(RequestContextSlot.APPSEC)
              .getCallback(EVENTS.databaseSqlQuery());
      if (sqlQueryCallback != null) {
        RequestContext ctx = span.getRequestContext();
        if (ctx != null) {
          Flow<Void> flow = sqlQueryCallback.apply(ctx, sql);
          Flow.Action action = flow.getAction();
          if (action instanceof Flow.Action.RequestBlockingAction) {
            BlockResponseFunction brf = ctx.getBlockResponseFunction();
            if (brf != null) {
              Flow.Action.RequestBlockingAction rba = (Flow.Action.RequestBlockingAction) action;
              brf.tryCommitBlockingResponse(ctx.getTraceSegment(), rba);
            }
            throw new BlockingException("Blocked request (for SQL query)");
          }
        }
      }
    }
  }

  protected void processDatabaseType(AgentSpan span, String dbType) {
    final NamingEntry namingEntry = CACHE.computeIfAbsent(dbType, NamingEntry::new);
    span.setTag(DB_TYPE, namingEntry.dbType);
    postProcessServiceAndOperationName(span, namingEntry);

    if (Config.get().isAppSecRaspEnabled() && dbType != null) {
      BiConsumer<RequestContext, String> connectDbCallback =
          AgentTracer.get()
              .getCallbackProvider(RequestContextSlot.APPSEC)
              .getCallback(EVENTS.databaseConnection());
      if (connectDbCallback != null) {
        RequestContext ctx = span.getRequestContext();
        if (ctx != null) {
          connectDbCallback.accept(ctx, dbType);
        }
      }
    }
  }

  protected void postProcessServiceAndOperationName(AgentSpan span, NamingEntry namingEntry) {}
}
