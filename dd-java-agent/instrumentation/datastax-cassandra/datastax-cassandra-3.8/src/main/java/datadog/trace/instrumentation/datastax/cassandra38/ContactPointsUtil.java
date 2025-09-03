package datadog.trace.instrumentation.datastax.cassandra38;

import com.datastax.driver.core.EndPoint;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

public class ContactPointsUtil {
  private static final DDCache<List<EndPoint>, String> CONTACT_POINT_CACHE =
      DDCaches.newFixedSizeCache(8);

  private static final Function<List<EndPoint>, String> ADDER =
      endPoints ->
          endPoints.stream()
              .map(EndPoint::resolve)
              .map(
                  inetSocketAddress ->
                      inetSocketAddress.getPort() > 0
                          ? inetSocketAddress.getHostString() + ":" + inetSocketAddress.getPort()
                          : inetSocketAddress.getHostString())
              .distinct() // avoid duplicates
              .collect(Collectors.joining(","));

  public static String fromEndPointList(@Nullable final List<EndPoint> contactPoints) {
    if (contactPoints == null) {
      return null;
    }
    return CONTACT_POINT_CACHE.computeIfAbsent(contactPoints, ADDER);
  }
}
