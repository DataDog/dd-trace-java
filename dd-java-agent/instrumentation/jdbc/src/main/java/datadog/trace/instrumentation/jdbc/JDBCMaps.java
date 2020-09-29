package datadog.trace.instrumentation.jdbc;

import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.jdbc.DBInfo;
import java.sql.Connection;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * JDBC instrumentation shares a global map of connection info.
 *
 * <p>Should be injected into the bootstrap classpath.
 */
public class JDBCMaps {
  // use a fixed size cache to avoid creating background cleanup work
  public static final DDCache<String, UTF8BytesString> preparedStatementsSql =
      DDCaches.newFixedSizeCache(256);
  // use a weak hash map and expunge when connections happen rather than in the background,
  // because connections are rare events in well written applications
  public static final Map<Connection, DBInfo> connectionInfo =
      Collections.synchronizedMap(new WeakHashMap<Connection, DBInfo>());
}
