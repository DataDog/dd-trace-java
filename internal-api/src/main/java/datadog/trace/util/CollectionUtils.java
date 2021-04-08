package datadog.trace.util;

import static datadog.trace.api.Platform.isJavaVersionAtLeast;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Collections;
import java.util.Set;

public final class CollectionUtils {

  private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
  private static final MethodHandle IMMUTABLE_COPY_OF_SET = findCopyOf(Set.class);

  @SuppressWarnings("unchecked")
  public static <T> Set<T> immutableSet(Set<T> set) {
    if (null != IMMUTABLE_COPY_OF_SET) {
      try {
        return (Set<T>) IMMUTABLE_COPY_OF_SET.invokeExact(set);
      } catch (Throwable ignore) {
      }
    }
    return Collections.unmodifiableSet(set);
  }

  private static MethodHandle findCopyOf(Class<?> clazz) {
    if (isJavaVersionAtLeast(10)) {
      try {
        return LOOKUP.findStatic(clazz, "copyOf", MethodType.methodType(clazz, clazz));
      } catch (NoSuchMethodException | IllegalAccessException ignore) {
      }
    }
    return null;
  }
}
