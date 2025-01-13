package datadog.trace.instrumentation.mule4;

import java.util.WeakHashMap;

public class JpmsAdvisingHelper {
  private static final WeakHashMap<Module, Boolean> ALREADY_PROCESSED_CACHE = new WeakHashMap<>();

  public static boolean isModuleAlreadyProcessed(final Module module) {
    return Boolean.TRUE.equals(ALREADY_PROCESSED_CACHE.putIfAbsent(module, Boolean.TRUE));
  }

  private JpmsAdvisingHelper() {}
}
