package datadog.trace.core.taginterceptor;

import datadog.trace.api.DDSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.DDSpanContext;

/**
 * This span decorator leverages DB tags. It allows the dev to define a custom service name and
 * retrieves some DB meta such as the statement
 */
@Deprecated // This should be covered by instrumentation decorators now.
class DBTypeTagInterceptor extends AbstractTagInterceptor {

  public DBTypeTagInterceptor() {
    super(Tags.DB_TYPE);
  }

  @Override
  public boolean shouldSetTag(final DDSpanContext context, final String tag, final Object value) {
    context.setServiceName(String.valueOf(value));

    // Assign service name
    if ("couchbase".equals(value) || "elasticsearch".equals(value)) {
      // these instrumentation have different behavior.
      return true;
    }
    // Assign span type to DB
    // Special case: Mongo, set to mongodb
    if ("mongo".equals(value)) {
      // Todo: not sure it's used cos already in the agent mongo helper
      context.setSpanType(DDSpanTypes.MONGO);
    } else if ("cassandra".equals(value)) {
      context.setSpanType(DDSpanTypes.CASSANDRA);
    } else if ("memcached".equals(value)) {
      context.setSpanType(DDSpanTypes.MEMCACHED);
    } else {
      context.setSpanType(DDSpanTypes.SQL);
    }
    // Works for: mongo, cassandra, jdbc
    context.setOperationName(String.valueOf(value) + ".query");

    return true;
  }
}
