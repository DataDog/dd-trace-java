package datadog.trace.util;

import static datadog.environment.JavaVirtualMachine.isJavaVersionAtLeast;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CollectionUtils {

  private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
  private static final MethodHandle IMMUTABLE_COPY_OF_SET = findCopyOf(Set.class, Collection.class);
  private static final MethodHandle IMMUTABLE_COPY_OF_LIST =
      findCopyOf(List.class, Collection.class);
  private static final MethodHandle IMMUTABLE_COPY_OF_MAP = findCopyOf(Map.class, Map.class);

  /**
   * Converts the input to an immutable set if available on the current platform. Otherwise returns
   * the input as a set.
   *
   * @param input the input.
   * @param <T> the type of the elements of the input.
   * @return an immutable copy of the input if possible.
   */
  @SuppressWarnings("unchecked")
  public static <T> Set<T> tryMakeImmutableSet(Collection<T> input) {
    if (null != IMMUTABLE_COPY_OF_SET) {
      try {
        return (Set<T>) IMMUTABLE_COPY_OF_SET.invokeExact(input);
      } catch (Throwable ignore) {
      }
    }
    return input instanceof Set ? (Set<T>) input : new HashSet<>(input);
  }

  /**
   * Converts the input to an immutable list if available on the current platform. Otherwise returns
   * the input as a list.
   *
   * @param input the input.
   * @param <T> the type of the elements of the input.
   * @return an immutable copy of the input if possible.
   */
  @SuppressWarnings("unchecked")
  public static <T> List<T> tryMakeImmutableList(Collection<T> input) {
    if (null != IMMUTABLE_COPY_OF_LIST) {
      try {
        return (List<T>) IMMUTABLE_COPY_OF_LIST.invokeExact(input);
      } catch (Throwable ignore) {
      }
    }
    return input instanceof List ? (List<T>) input : new ArrayList<>(input);
  }

  /**
   * Converts the input to an immutable map if available on the current platform. Otherwise returns
   * the input.
   *
   * @param input the input.
   * @param <K> the key type
   * @param <V> the value type
   * @return an immutable copy of the input if possible.
   */
  @SuppressWarnings("unchecked")
  public static <K, V> Map<K, V> tryMakeImmutableMap(Map<K, V> input) {
    if (null != IMMUTABLE_COPY_OF_MAP) {
      try {
        return (Map<K, V>) IMMUTABLE_COPY_OF_MAP.invokeExact(input);
      } catch (Throwable ignore) {
      }
    }
    return input;
  }

  private static MethodHandle findCopyOf(Class<?> clazz, Class<?> arg) {
    if (isJavaVersionAtLeast(10)) {
      try {
        return LOOKUP.findStatic(clazz, "copyOf", MethodType.methodType(clazz, arg));
      } catch (NoSuchMethodException | IllegalAccessException ignore) {
      }
    }
    return null;
  }
}
