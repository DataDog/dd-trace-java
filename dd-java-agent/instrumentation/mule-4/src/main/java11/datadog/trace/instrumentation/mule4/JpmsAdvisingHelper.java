package datadog.trace.instrumentation.mule4;

import java.util.WeakHashMap;

public class JpmsAdvisingHelper {
  private static final WeakHashMap<Class<?>, Boolean> ALREADY_PROCESSED_CACHE = new WeakHashMap<>();

  public static void allowAccessOnModuleClass(final Class<?> cls) {
    if (Boolean.TRUE.equals(ALREADY_PROCESSED_CACHE.putIfAbsent(cls, Boolean.TRUE))) {
      return;
    }
    final Module module = cls.getModule();
    if (module != null) {
      try {
        module.addExports(cls.getPackageName(), module.getClassLoader().getUnnamedModule());
      } catch (Throwable ignored) {
      }
    }
  }

  private JpmsAdvisingHelper() {}
}
