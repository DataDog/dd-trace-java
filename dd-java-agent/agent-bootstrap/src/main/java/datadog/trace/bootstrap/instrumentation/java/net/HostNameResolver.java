package datadog.trace.bootstrap.instrumentation.java.net;

import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.util.MethodHandles;
import java.lang.invoke.MethodHandle;
import java.net.InetAddress;

public final class HostNameResolver {
  private static volatile MethodHandle HOLDER_GET;
  private static volatile MethodHandle HOSTNAME_GET;

  private static final DDCache<String, String> HOSTNAME_CACHE = DDCaches.newFixedSizeCache(64);

  private HostNameResolver() {}

  public static void tryInitialize() {
    if (HOLDER_GET != null) {
      return; // fast path: already initialized
    }
    synchronized (HostNameResolver.class) {
      if (HOLDER_GET != null) {
        return; // double-check: another thread just succeeded
      }
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
      }
      // volatile writes ensure visibility to other threads
      if (holderTmp != null && hostnameTmp != null) {
        HOSTNAME_GET = hostnameTmp;
        HOLDER_GET = holderTmp; // written last: signals successful initialization
      }
    }
  }

  static String getAlreadyResolvedHostName(InetAddress address) {
    if (HOLDER_GET == null) {
      tryInitialize();
    }
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
