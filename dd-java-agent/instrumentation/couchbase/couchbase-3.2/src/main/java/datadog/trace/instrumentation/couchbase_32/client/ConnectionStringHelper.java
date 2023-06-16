package datadog.trace.instrumentation.couchbase_32.client;

import com.couchbase.client.core.util.ConnectionString;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import java.util.function.Function;
import java.util.stream.Collectors;

public class ConnectionStringHelper {
  private static final DDCache<ConnectionString, String> CONNECTION_STRING_STRING_CACHE =
      DDCaches.newFixedSizeCache(8);

  private static final Function<ConnectionString, String> ADDER =
      cs ->
          cs.hosts().stream()
              .map(ConnectionString.UnresolvedSocket::hostname)
              .distinct()
              .collect(Collectors.joining(","));

  public static String toHostPortList(final ConnectionString connectionString) {
    if (connectionString == null) {
      return null;
    }
    return CONNECTION_STRING_STRING_CACHE.computeIfAbsent(connectionString, ADDER);
  }
}
