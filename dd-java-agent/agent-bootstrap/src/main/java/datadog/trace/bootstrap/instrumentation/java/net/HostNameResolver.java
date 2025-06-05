package datadog.trace.bootstrap.instrumentation.java.net;

import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.util.MethodHandles;
import java.lang.invoke.MethodHandle;
import java.net.InetAddress;

public final class HostNameResolver {
  private static final MethodHandle HOLDER_GET;
  private static final MethodHandle HOSTNAME_GET;

  private static final DDCache<String, String> HOSTNAME_CACHE = DDCaches.newFixedSizeCache(64);

  static {
    MethodHandle holderTmp = null, hostnameTmp = null;
    try {
      final ClassLoader cl = HostNameResolver.class.getClassLoader();
      final MethodHandles methodHandles = new MethodHandles(cl);

      final Class<?> holderClass =
          Class.forName("java.net.InetAddress$InetAddressHolder", false, cl);
      holderTmp = methodHandles.method(InetAddress.class, "holder");
      if (holderTmp != null) {
        hostnameTmp = methodHandles.method(holderClass, "getHostName");
      }
    } catch (Throwable ignored) {
      holderTmp = null;
    } finally {
      if (holderTmp != null && hostnameTmp != null) {
        HOLDER_GET = holderTmp;
        HOSTNAME_GET = hostnameTmp;
      } else {
        HOLDER_GET = null;
        HOSTNAME_GET = null;
      }
    }
  }

  private HostNameResolver() {}

  static String getAlreadyResolvedHostName(InetAddress address) {
    if (HOLDER_GET == null) {
      return null;
    }
    try {
      final Object holder = HOLDER_GET.invoke(address);
      return (String) HOSTNAME_GET.invoke(holder);
    } catch (final Throwable ignored) {
    }
    return null;
  }

  private static String fromCache(InetAddress remoteAddress, String ip) {
    if (null != ip) {
      return HOSTNAME_CACHE.computeIfAbsent(ip, _ip -> remoteAddress.getHostName());
    }
    return remoteAddress.getHostName();
  }

  public static String hostName(InetAddress address, String ip) {
    final String alreadyResolved = getAlreadyResolvedHostName(address);
    if (alreadyResolved != null) {
      return alreadyResolved;
    }
    return fromCache(address, ip);
  }
}
