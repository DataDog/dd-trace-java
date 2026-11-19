package com.datadog.openfeature;

import dev.openfeature.sdk.HookContext;
import java.lang.reflect.Method;

/** Compatibility access to hook-local data, which was added after OpenFeature SDK 1.17. */
final class HookDataAccessor {
  private static final Methods METHODS = Methods.resolve();

  private HookDataAccessor() {}

  static boolean isAvailable() {
    return METHODS != null;
  }

  static Object get(final HookContext<?> context, final String key) {
    if (METHODS == null || context == null) {
      return null;
    }
    try {
      final Object hookData = METHODS.contextGet.invoke(context);
      return hookData == null ? null : METHODS.dataGet.invoke(hookData, key);
    } catch (final ReflectiveOperationException | RuntimeException | LinkageError ignored) {
      return null;
    }
  }

  static boolean set(final HookContext<?> context, final String key, final Object value) {
    if (METHODS == null || context == null) {
      return false;
    }
    try {
      final Object hookData = METHODS.contextGet.invoke(context);
      if (hookData != null) {
        METHODS.dataSet.invoke(hookData, key, value);
        return true;
      }
    } catch (final ReflectiveOperationException | RuntimeException | LinkageError ignored) {
      // The caller handles missing lifecycle storage as a best-effort telemetry failure.
    }
    return false;
  }

  static Object take(final HookContext<?> context, final String key) {
    return get(context, key);
  }

  private static final class Methods {
    private final Method contextGet;
    private final Method dataGet;
    private final Method dataSet;

    private Methods(final Method contextGet, final Method dataGet, final Method dataSet) {
      this.contextGet = contextGet;
      this.dataGet = dataGet;
      this.dataSet = dataSet;
    }

    private static Methods resolve() {
      try {
        final Method contextGet = HookContext.class.getMethod("getHookData");
        final Class<?> hookData = contextGet.getReturnType();
        return new Methods(
            contextGet,
            hookData.getMethod("get", String.class),
            hookData.getMethod("set", String.class, Object.class));
      } catch (final ReflectiveOperationException | RuntimeException | LinkageError ignored) {
        return null;
      }
    }
  }
}
