package datadog.trace.bootstrap.debugger.util;

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
  private static Set<String> toStringFinalSafeClasses =
      new HashSet<>(
          Arrays.asList(
              "java.lang.Class",
              "java.lang.String",
              "java.lang.Boolean",
              "java.lang.Integer",
              "java.lang.Long",
              "java.lang.Double",
              "java.lang.Character",
              "java.lang.Byte",
              "java.lang.Float",
              "java.lang.Short",
              "java.math.BigDecimal",
              "java.math.BigInteger",
              "java.time.Duration",
              "java.time.Instant",
              "java.time.LocalTime",
              "java.time.LocalDate",
              "java.time.LocalDateTime",
              "java.util.UUID",
              "java.net.URI"));

  private static Set<String> toStringSafeClasses = new HashSet<>();

  static {
    toStringSafeClasses.addAll(toStringFinalSafeClasses);
    toStringSafeClasses.addAll(
        Arrays.asList(
            "java.util.concurrent.atomic.AtomicBoolean",
            "java.util.concurrent.atomic.AtomicInteger",
            "java.util.concurrent.atomic.AtomicLong"));
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

  private static Map<String, Function<Object, SpecialField>> specialFields = new HashMap<>();

  static {
    specialFields.put("java.util.Optional", WellKnownClasses::optionalSpecialField);
  }

  /**
   * @return true if type is a final class and toString implementation is well known and side effect
   *     free
   */
  public static boolean isToStringFinalSafe(String type) {
    return toStringFinalSafeClasses.contains(type);
  }

  /**
   * @return true if type is a class with a toString implementation is well known and side effect
   *     free, but input type name should be a dynamic/concrete type and not a declared type. some
   *     classes are not final and could be overridden toString
   */
  public static boolean isToStringSafe(String concreteType) {
    return toStringSafeClasses.contains(concreteType);
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
  public static Function<Object, SpecialField> getSpecialFieldAccess(String type) {
    return specialFields.get(type);
  }

  private static SpecialField optionalSpecialField(Object o) {
    return new SpecialField("value", Object.class.getTypeName(), ((Optional<?>) o).orElse(null));
  }

  public static class SpecialField {
    private final String name;
    private final String type;
    private final Object value;

    public SpecialField(String name, String type, Object value) {
      this.name = name;
      this.type = type;
      this.value = value;
    }

    public String getName() {
      return name;
    }

    public String getType() {
      return type;
    }

    public Object getValue() {
      return value;
    }
  }
}
