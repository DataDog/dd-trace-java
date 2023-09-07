package datadog.trace.instrumentation.datastax.cassandra4;

import com.datastax.oss.driver.api.core.metadata.EndPoint;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import java.net.InetSocketAddress;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

public class ContactPointsUtil {
  private static final DDCache<Set<EndPoint>, String> CONTACT_POINT_CACHE =
      DDCaches.newFixedSizeCache(8);

  private static final Function<Set<EndPoint>, String> ADDER =
      endPoints ->
          endPoints.stream()
              .map(EndPoint::resolve)
              .filter(InetSocketAddress.class::isInstance)
              .map(InetSocketAddress.class::cast)
              .map(
                  inetSocketAddress -> {
                    if (inetSocketAddress.getPort() > 0) {
                      return inetSocketAddress.getHostString() + ":" + inetSocketAddress.getPort();
                    }
                    return inetSocketAddress.getHostString();
                  })
              .distinct() // avoid duplicates
              .collect(Collectors.joining(","));

  public static String fromEndPointSet(@Nullable final Set<EndPoint> contactPoints) {
    if (contactPoints == null) {
      return null;
    }
    return CONTACT_POINT_CACHE.computeIfAbsent(contactPoints, ADDER);
  }
}
