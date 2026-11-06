package com.datadog.openfeature;

import dev.openfeature.sdk.HookContext;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/** Compatibility access to hook-local data, which was added after OpenFeature SDK 1.17. */
final class HookDataAccessor {
  private static final Methods METHODS = Methods.resolve();
  private static final ThreadLocal<Map<HookContext<?>, Map<String, Object>>> FALLBACK =
      new ThreadLocal<>();

  private HookDataAccessor() {}

  static boolean isAvailable() {
    return METHODS != null;
  }

  static Object get(final HookContext<?> context, final String key) {
    if (context == null) {
      return null;
    }
    if (METHODS != null) {
      try {
        final Object hookData = METHODS.contextGet.invoke(context);
        final Object value = hookData == null ? null : METHODS.dataGet.invoke(hookData, key);
        if (value != null) {
          return value;
        }
      } catch (final ReflectiveOperationException | RuntimeException | LinkageError ignored) {
        // Fall through to the compatibility storage used by older SDKs.
      }
    }
    final Map<HookContext<?>, Map<String, Object>> contexts = FALLBACK.get();
    final Map<String, Object> values = contexts == null ? null : contexts.get(context);
    return values == null ? null : values.get(key);
  }

  static boolean set(final HookContext<?> context, final String key, final Object value) {
    if (context == null) {
      return false;
    }
    if (METHODS != null) {
      try {
        final Object hookData = METHODS.contextGet.invoke(context);
        if (hookData != null) {
          METHODS.dataSet.invoke(hookData, key, value);
          return true;
        }
      } catch (final ReflectiveOperationException | RuntimeException | LinkageError ignored) {
        // Fall through to the compatibility storage used by older SDKs.
      }
    }
    Map<HookContext<?>, Map<String, Object>> contexts = FALLBACK.get();
    if (contexts == null) {
      contexts = new IdentityHashMap<>();
      FALLBACK.set(contexts);
    }
    Map<String, Object> values = contexts.get(context);
    if (values == null) {
      values = new HashMap<>();
      contexts.put(context, values);
    }
    values.put(key, value);
    return true;
  }

  static Object take(final HookContext<?> context, final String key) {
    final Object value = get(context, key);
    final Map<HookContext<?>, Map<String, Object>> contexts = FALLBACK.get();
    final Map<String, Object> values = contexts == null ? null : contexts.get(context);
    if (values != null) {
      values.remove(key);
      if (values.isEmpty()) {
        contexts.remove(context);
      }
      if (contexts.isEmpty()) {
        FALLBACK.remove();
      }
    }
    return value;
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
