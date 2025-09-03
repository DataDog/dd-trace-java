package datadog.trace.instrumentation.datastax.cassandra;

import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

public class ContactPointsUtil {
  private static final DDCache<List<InetSocketAddress>, String> CONTACT_POINT_CACHE =
      DDCaches.newFixedSizeCache(8);

  private static final Function<List<InetSocketAddress>, String> ADDER =
      inetSocketAddresses ->
          inetSocketAddresses.stream()
              .map(
                  inetSocketAddress ->
                      inetSocketAddress.getPort() > 0
                          ? inetSocketAddress.getHostString() + ":" + inetSocketAddress.getPort()
                          : inetSocketAddress.getHostString())
              .distinct()
              .collect(Collectors.joining(","));

  public static String fromInetSocketList(@Nullable final List<InetSocketAddress> contactPoints) {
    if (contactPoints == null) {
      return null;
    }
    return CONTACT_POINT_CACHE.computeIfAbsent(contactPoints, ADDER);
  }
}
