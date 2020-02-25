package datadog.trace.agent.tooling.bytebuddy.matcher;

import java.util.HashMap;
import java.util.Map;

public class ThreadLocalCache {

  private static final ThreadLocal<Map<String, Boolean>> superTypeCache =
    new ThreadLocal<Map<String, Boolean>>() {
      @Override
      protected Map<String, Boolean> initialValue() {
        return new HashMap<String, Boolean>();
      }
    };

  public static Boolean get(String key) {
    return superTypeCache.get().get(key);
  }

  public static void put(String key, boolean value) {
    superTypeCache.get().put(key, value);
  }

  public static void clearCache() {
    superTypeCache.remove();
  }
}
