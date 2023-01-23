package com.datadog.debugger.util;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

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
}
