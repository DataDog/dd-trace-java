package datadog.trace.agent.tooling.bytebuddy.matcher;

public class GlobalIgnoresCache {

  private static long cache = 0;

  private static void store(long newCache) {
    cache = newCache;
  }

  private static Long getCachedValue() {
    return cache;
  }

  private static long build(String key, int allowCode) {
    return (((long) allowCode) << 32) | (System.identityHashCode(key) & 0xffffffffL);
  }

  private static int getAllowCode(long cached) {
    return (int) (cached >> 32);
  }

  private static int getIdentityHash(long cached) {
    return (int) cached;
  }

  public static int getAllowCode(String name) {
    Integer allowCode = null;
    Long cachedValue = getCachedValue();
    if (getIdentityHash(cachedValue) == System.identityHashCode(name)) {
      allowCode = getAllowCode(cachedValue);
    }
    if (null == allowCode) {
      allowCode = IgnoredClassNameTrie.apply(name);
      store(GlobalIgnoresCache.build(name, allowCode));
    }
    return allowCode;
  }
}
