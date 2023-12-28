package datadog.trace.bootstrap.debugger.util;

import datadog.trace.bootstrap.debugger.CapturedContext;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

public class WellKnownClasses {

  /** Set of class names which have a toString side effect free and class final */
  private static Map<String, Function<Object, String>> toStringFinalSafeClasses = new HashMap<>();

  static {
    toStringFinalSafeClasses.put("java.lang.Class", WellKnownClasses::classToString);
    toStringFinalSafeClasses.put("java.lang.String", WellKnownClasses::genericToString);
    toStringFinalSafeClasses.put("java.lang.Boolean", WellKnownClasses::genericToString);
    toStringFinalSafeClasses.put("java.lang.Integer", WellKnownClasses::genericToString);
    toStringFinalSafeClasses.put("java.lang.Long", WellKnownClasses::genericToString);
    toStringFinalSafeClasses.put("java.lang.Double", WellKnownClasses::genericToString);
    toStringFinalSafeClasses.put("java.lang.Character", WellKnownClasses::genericToString);
    toStringFinalSafeClasses.put("java.lang.Byte", WellKnownClasses::genericToString);
    toStringFinalSafeClasses.put("java.lang.Float", WellKnownClasses::genericToString);
    toStringFinalSafeClasses.put("java.lang.Short", WellKnownClasses::genericToString);
    toStringFinalSafeClasses.put("java.math.BigDecimal", WellKnownClasses::genericToString);
    toStringFinalSafeClasses.put("java.math.BigInteger", WellKnownClasses::genericToString);
    toStringFinalSafeClasses.put("java.time.Duration", WellKnownClasses::genericToString);
    toStringFinalSafeClasses.put("java.time.Instant", WellKnownClasses::genericToString);
    toStringFinalSafeClasses.put("java.time.LocalTime", WellKnownClasses::genericToString);
    toStringFinalSafeClasses.put("java.time.LocalDate", WellKnownClasses::genericToString);
    toStringFinalSafeClasses.put("java.time.LocalDateTime", WellKnownClasses::genericToString);
    toStringFinalSafeClasses.put("java.util.UUID", WellKnownClasses::genericToString);
    toStringFinalSafeClasses.put("java.net.URI", WellKnownClasses::genericToString);
  }

  private static Map<String, Function<Object, String>> safeToStringFunctions = new HashMap<>();

  static {
    safeToStringFunctions.putAll(toStringFinalSafeClasses);
    safeToStringFunctions.put(
        "java.util.concurrent.atomic.AtomicBoolean", WellKnownClasses::genericToString);
    safeToStringFunctions.put(
        "java.util.concurrent.atomic.AtomicInteger", WellKnownClasses::genericToString);
    safeToStringFunctions.put(
        "java.util.concurrent.atomic.AtomicLong", WellKnownClasses::genericToString);
  }

  private static Set<String> stringPrimitives =
      new HashSet<>(
          Arrays.asList(
              "java.lang.Class",
              "java.lang.String",
              "java.time.Duration",
              "java.time.Instant",
              "java.time.LocalTime",
              "java.time.LocalDate",
              "java.time.LocalDateTime",
              "java.util.UUID"));

  private static Map<String, Function<Object, CapturedContext.CapturedValue>> specialFields =
      new HashMap<>();

  static {
    specialFields.put("java.util.Optional", WellKnownClasses::optionalSpecialField);
  }

  /**
   * @return true if type is a final class and toString implementation is well known and side effect
   *     free
   */
  public static boolean isToStringFinalSafe(String type) {
    return toStringFinalSafeClasses.containsKey(type);
  }

  /**
   * @return true if type is a class with a toString implementation is well known and side effect
   *     free, but input type name should be a dynamic/concrete type and not a declared type. some
   *     classes are not final and could be overridden toString
   */
  public static boolean isToStringSafe(String concreteType) {
    return safeToStringFunctions.containsKey(concreteType);
  }

  /**
   * @return true if collection is the implementation of size method is side effect free and O(1)
   *     complexity
   */
  public static boolean isSizeSafe(Collection<?> collection) {
    String className = collection.getClass().getTypeName();
    if (className.startsWith("java.")) {
      // All Collection implementations from JDK base module are considered as safe
      return true;
    }
    return false;
  }

  /**
   * @return true if map is the implementation of size method is side effect free and O(1)
   *     complexity
   */
  public static boolean isSizeSafe(Map<?, ?> map) {
    String className = map.getClass().getTypeName();
    if (className.startsWith("java.")) {
      // All Map implementations from JDK base module are considered as safe
      return true;
    }
    return false;
  }

  /**
   * indicates if type is considered as a string primitive and can be compared to a string literal
   * with Expression Language
   */
  public static boolean isStringPrimitive(String type) {
    return stringPrimitives.contains(type);
  }

  /**
   * @return a function to access special field of a type, or null if type is not supported. This is
   *     used to avoid using reflection to access fields on well known types
   */
  public static Function<Object, CapturedContext.CapturedValue> getSpecialFieldAccess(String type) {
    return specialFields.get(type);
  }

  /**
   * @return a function to generate a string representation of a type where the default toString
   *     method is not suitable
   */
  public static Function<Object, String> getSafeToString(String type) {
    return safeToStringFunctions.get(type);
  }

  private static CapturedContext.CapturedValue optionalSpecialField(Object o) {
    return CapturedContext.CapturedValue.of(
        "value", Object.class.getTypeName(), ((Optional<?>) o).orElse(null));
  }

  private static String classToString(Object o) {
    return ((Class<?>) o).getTypeName();
  }

  private static String genericToString(Object o) {
    return String.valueOf(o);
  }
}
