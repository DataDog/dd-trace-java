package datadog.trace.core.taginterceptor;

import datadog.trace.api.DDSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.DDSpanContext;
import datadog.trace.core.util.FixedSizeCache;

/**
 * This span decorator leverages DB tags. It allows the dev to define a custom service name and
 * retrieves some DB meta such as the statement
 */
@Deprecated // This should be covered by instrumentation decorators now.
class DBTypeTagInterceptor extends AbstractTagInterceptor {

  public DBTypeTagInterceptor() {
    super(Tags.DB_TYPE);
  }

  private static final String OPERATION_SUFFIX = ".query";

  // The total number of entries in the cache will normally be less than 4, since
  // most applications only have one or two DBs, and "jdbc" itself is also used as
  // one DB_TYPE, but set the cache size to 16 to help avoid collisions.
  private final FixedSizeCache<String, String> cache = new FixedSizeCache<>(16);
  private final FixedSizeCache.Creator<String, String> appendOperationSuffix =
      new FixedSizeCache.Creator<String, String>() {
        @Override
        public String create(String key) {
          return key + OPERATION_SUFFIX;
        }
      };

  @Override
  public boolean shouldSetTag(final DDSpanContext context, final String tag, final Object value) {
    final String serviceName = String.valueOf(value);

    // Assign service name
    context.setServiceName(serviceName);

    if ("couchbase".equals(serviceName) || "elasticsearch".equals(serviceName)) {
      // these instrumentation have different behavior.
      return true;
    }
    // Assign span type to DB
    // Special case: Mongo, set to mongodb
    if ("mongo".equals(serviceName)) {
      // Todo: not sure it's used cos already in the agent mongo helper
      context.setSpanType(DDSpanTypes.MONGO);
    } else if ("cassandra".equals(serviceName)) {
      context.setSpanType(DDSpanTypes.CASSANDRA);
    } else if ("memcached".equals(serviceName)) {
      context.setSpanType(DDSpanTypes.MEMCACHED);
    } else {
      context.setSpanType(DDSpanTypes.SQL);
    }
    // Works for: mongo, cassandra, jdbc
    String operationName = cache.computeIfAbsent(serviceName, appendOperationSuffix);
    context.setOperationName(operationName);

    return true;
  }
}
