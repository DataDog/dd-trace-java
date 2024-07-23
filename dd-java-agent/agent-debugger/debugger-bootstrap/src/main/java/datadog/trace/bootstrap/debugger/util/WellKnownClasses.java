package datadog.trace.bootstrap.debugger.util;

import datadog.trace.bootstrap.debugger.CapturedContext;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WellKnownClasses {
  private static final Logger LOGGER = LoggerFactory.getLogger(WellKnownClasses.class);

  /** Set of class names which have a toString side effect free and class final */
  private static final Map<String, Function<Object, String>> TO_STRING_FINAL_SAFE_CLASSES =
      new HashMap<>();

  static {
    TO_STRING_FINAL_SAFE_CLASSES.put("java.lang.Class", WellKnownClasses::classToString);
    TO_STRING_FINAL_SAFE_CLASSES.put("java.lang.String", WellKnownClasses::genericToString);
    TO_STRING_FINAL_SAFE_CLASSES.put("java.lang.Boolean", WellKnownClasses::genericToString);
    TO_STRING_FINAL_SAFE_CLASSES.put("java.lang.Integer", WellKnownClasses::genericToString);
    TO_STRING_FINAL_SAFE_CLASSES.put("java.lang.Long", WellKnownClasses::genericToString);
    TO_STRING_FINAL_SAFE_CLASSES.put("java.lang.Double", WellKnownClasses::genericToString);
    TO_STRING_FINAL_SAFE_CLASSES.put("java.lang.Character", WellKnownClasses::genericToString);
    TO_STRING_FINAL_SAFE_CLASSES.put("java.lang.Byte", WellKnownClasses::genericToString);
    TO_STRING_FINAL_SAFE_CLASSES.put("java.lang.Float", WellKnownClasses::genericToString);
    TO_STRING_FINAL_SAFE_CLASSES.put("java.lang.Short", WellKnownClasses::genericToString);
    TO_STRING_FINAL_SAFE_CLASSES.put("java.math.BigDecimal", WellKnownClasses::genericToString);
    TO_STRING_FINAL_SAFE_CLASSES.put("java.math.BigInteger", WellKnownClasses::genericToString);
    TO_STRING_FINAL_SAFE_CLASSES.put("java.time.Duration", WellKnownClasses::genericToString);
    TO_STRING_FINAL_SAFE_CLASSES.put("java.time.Instant", WellKnownClasses::genericToString);
    TO_STRING_FINAL_SAFE_CLASSES.put("java.time.LocalTime", WellKnownClasses::genericToString);
    TO_STRING_FINAL_SAFE_CLASSES.put("java.time.LocalDate", WellKnownClasses::genericToString);
    TO_STRING_FINAL_SAFE_CLASSES.put("java.time.LocalDateTime", WellKnownClasses::genericToString);
    TO_STRING_FINAL_SAFE_CLASSES.put("java.util.UUID", WellKnownClasses::genericToString);
    TO_STRING_FINAL_SAFE_CLASSES.put("java.net.URI", WellKnownClasses::genericToString);
  }

  private static final Map<String, Function<Object, String>> SAFE_TO_STRING_FUNCTIONS =
      new HashMap<>();

  static {
    SAFE_TO_STRING_FUNCTIONS.putAll(TO_STRING_FINAL_SAFE_CLASSES);
    SAFE_TO_STRING_FUNCTIONS.put(
        "java.util.concurrent.atomic.AtomicBoolean", WellKnownClasses::genericToString);
    SAFE_TO_STRING_FUNCTIONS.put(
        "java.util.concurrent.atomic.AtomicInteger", WellKnownClasses::genericToString);
    SAFE_TO_STRING_FUNCTIONS.put(
        "java.util.concurrent.atomic.AtomicLong", WellKnownClasses::genericToString);
  }

  private static final Set<String> EQUALS_SAFE_CLASSES = new HashSet<>();

  static {
    EQUALS_SAFE_CLASSES.add("java.lang.Class");
    EQUALS_SAFE_CLASSES.add("java.lang.String");
    EQUALS_SAFE_CLASSES.add("java.lang.Boolean");
    EQUALS_SAFE_CLASSES.add("java.lang.Integer");
    EQUALS_SAFE_CLASSES.add("java.lang.Long");
    EQUALS_SAFE_CLASSES.add("java.lang.Double");
    EQUALS_SAFE_CLASSES.add("java.lang.Character");
    EQUALS_SAFE_CLASSES.add("java.lang.Byte");
    EQUALS_SAFE_CLASSES.add("java.lang.Float");
    EQUALS_SAFE_CLASSES.add("java.lang.Short");
    EQUALS_SAFE_CLASSES.add("java.math.BigDecimal");
    EQUALS_SAFE_CLASSES.add("java.math.BigInteger");
    EQUALS_SAFE_CLASSES.add("java.time.Duration");
    EQUALS_SAFE_CLASSES.add("java.time.Instant");
    EQUALS_SAFE_CLASSES.add("java.time.LocalTime");
    EQUALS_SAFE_CLASSES.add("java.time.LocalDate");
    EQUALS_SAFE_CLASSES.add("java.time.LocalDateTime");
    EQUALS_SAFE_CLASSES.add("java.util.UUID");
    EQUALS_SAFE_CLASSES.add("java.net.URI");
  }

  private static final Set<String> STRING_PRIMITIVES =
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

  private static final Map<Class<?>, Map<String, Function<Object, CapturedContext.CapturedValue>>>
      SPECIAL_TYPE_ACCESS = new HashMap<>();

  private static final Map<String, Function<Object, CapturedContext.CapturedValue>>
      STACKTRACEELEMENT_SPECIAL_FIELDS = new HashMap<>();

  private static Method getModuleNameMethod;

  static {
    STACKTRACEELEMENT_SPECIAL_FIELDS.put("declaringClass", StackTraceElementFields::declaringClass);
    STACKTRACEELEMENT_SPECIAL_FIELDS.put("methodName", StackTraceElementFields::methodName);
    STACKTRACEELEMENT_SPECIAL_FIELDS.put("fileName", StackTraceElementFields::fileName);
    STACKTRACEELEMENT_SPECIAL_FIELDS.put("lineNumber", StackTraceElementFields::lineNumber);
    STACKTRACEELEMENT_SPECIAL_FIELDS.put("moduleName", StackTraceElementFields::moduleName);
    try {
      getModuleNameMethod = StackTraceElement.class.getMethod("getModuleName");
    } catch (NoSuchMethodException e) {
      getModuleNameMethod = null;
    }
    ;
  }

  private static final Map<String, Function<Object, CapturedContext.CapturedValue>>
      OPTIONAL_SPECIAL_FIELDS = new HashMap<>();

  static {
    OPTIONAL_SPECIAL_FIELDS.put("value", OptionalFields::value);
  }

  static {
    SPECIAL_TYPE_ACCESS.put(StackTraceElement.class, STACKTRACEELEMENT_SPECIAL_FIELDS);
    SPECIAL_TYPE_ACCESS.put(Optional.class, OPTIONAL_SPECIAL_FIELDS);
  }

  private static final Map<String, Function<Object, CapturedContext.CapturedValue>>
      THROWABLE_SPECIAL_FIELDS = new HashMap<>();

  static {
    THROWABLE_SPECIAL_FIELDS.put("detailMessage", ThrowableFields::detailMessage);
    THROWABLE_SPECIAL_FIELDS.put("suppressedExceptions", ThrowableFields::suppressedExceptions);
    THROWABLE_SPECIAL_FIELDS.put("stackTrace", ThrowableFields::stackTrace);
    THROWABLE_SPECIAL_FIELDS.put("cause", ThrowableFields::cause);
  }

  /**
   * @return true if type is a final class and toString implementation is well known and side effect
   *     free
   */
  public static boolean isToStringFinalSafe(String type) {
    return TO_STRING_FINAL_SAFE_CLASSES.containsKey(type);
  }

  /**
   * @return true if type is a class with a toString implementation is well known and side effect
   *     free, but input type name should be a dynamic/concrete type and not a declared type. some
   *     classes are not final and could be overridden toString
   */
  public static boolean isToStringSafe(String concreteType) {
    return SAFE_TO_STRING_FUNCTIONS.containsKey(concreteType);
  }

  /** @return true if collection implementation is safe to call (only in-memory) */
  public static boolean isSafe(Collection<?> collection) {
    String className = collection.getClass().getTypeName();
    if (className.startsWith("java.")) {
      // All Collection implementations from JDK base module are considered as safe
      return true;
    }
    return false;
  }

  /** @return true if map implementation is safe to call (only in-memory) */
  public static boolean isSafe(Map<?, ?> map) {
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
    return STRING_PRIMITIVES.contains(type);
  }

  /**
   * @return a map of fields with function to access special field of a type, or null if type is not
   *     supported. This is used to avoid using reflection to access fields on well known types
   */
  public static Map<String, Function<Object, CapturedContext.CapturedValue>> getSpecialTypeAccess(
      Object value) {
    if (value == null) {
      return null;
    }
    Map<String, Function<Object, CapturedContext.CapturedValue>> specialTypeAccess =
        SPECIAL_TYPE_ACCESS.get(value.getClass());
    if (specialTypeAccess != null) {
      return specialTypeAccess;
    }
    if (value instanceof Throwable) {
      return THROWABLE_SPECIAL_FIELDS;
    }
    return null;
  }

  /**
   * @return a function to generate a string representation of a type where the default toString
   *     method is not suitable
   */
  public static Function<Object, String> getSafeToString(String type) {
    return SAFE_TO_STRING_FUNCTIONS.get(type);
  }

  private static String classToString(Object o) {
    return ((Class<?>) o).getTypeName();
  }

  private static String genericToString(Object o) {
    return String.valueOf(o);
  }

  public static boolean isEqualsSafe(Class<?> clazz) {
    return clazz.isPrimitive()
        || clazz.isEnum()
        || EQUALS_SAFE_CLASSES.contains(clazz.getTypeName());
  }

  private static class ThrowableFields {
    public static final String BECAUSE_OVERRIDDEN =
        "Special access method not safe to be called because overridden";

    public static CapturedContext.CapturedValue detailMessage(Object o) {
      return captureIfNotOverridden(
          (Throwable) o,
          "detailMessage",
          "getMessage",
          String.class,
          Throwable.class,
          Throwable::getMessage);
    }

    public static CapturedContext.CapturedValue suppressedExceptions(Object o) {
      return CapturedContext.CapturedValue.of(
          "suppressedExceptions", String.class.getTypeName(), ((Throwable) o).getSuppressed());
    }

    public static CapturedContext.CapturedValue stackTrace(Object o) {
      return captureIfNotOverridden(
          (Throwable) o,
          "stackTrace",
          "getStackTrace",
          StackTraceElement[].class,
          Throwable.class,
          Throwable::getStackTrace);
    }

    public static CapturedContext.CapturedValue cause(Object o) {
      return captureIfNotOverridden(
          (Throwable) o,
          "cause",
          "getCause",
          Throwable.class,
          Throwable.class,
          Throwable::getCause);
    }

    private static <T, R> CapturedContext.CapturedValue captureIfNotOverridden(
        T obj,
        String fieldName,
        String methodName,
        Class<R> fieldType,
        Class<T> orginalDeclaringClass,
        Function<T, R> supplier) {
      if (isOverridden(obj, methodName, orginalDeclaringClass)) {
        return CapturedContext.CapturedValue.notCapturedReason(
            fieldName, fieldType.getTypeName(), BECAUSE_OVERRIDDEN);
      }
      return CapturedContext.CapturedValue.of(
          fieldName, fieldType.getTypeName(), supplier.apply(obj));
    }

    private static boolean isOverridden(
        Object value, String methodName, Class<?> originalDeclaringClass) {
      Class<?> declaringClass = null;
      try {
        declaringClass = value.getClass().getMethod(methodName).getDeclaringClass();
      } catch (NoSuchMethodException e) {
        LOGGER.debug("Failed to get declaring class for Throwable::getMessage", e);
      }
      return declaringClass != originalDeclaringClass;
    }
  }

  private static class StackTraceElementFields {
    public static CapturedContext.CapturedValue declaringClass(Object o) {
      return CapturedContext.CapturedValue.of(
          "declaringClass", String.class.getTypeName(), ((StackTraceElement) o).getClassName());
    }

    public static CapturedContext.CapturedValue methodName(Object o) {
      return CapturedContext.CapturedValue.of(
          "methodName", String.class.getTypeName(), ((StackTraceElement) o).getMethodName());
    }

    public static CapturedContext.CapturedValue fileName(Object o) {
      return CapturedContext.CapturedValue.of(
          "fileName", String.class.getTypeName(), ((StackTraceElement) o).getFileName());
    }

    public static CapturedContext.CapturedValue lineNumber(Object o) {
      return CapturedContext.CapturedValue.of(
          "lineNumber", String.class.getTypeName(), ((StackTraceElement) o).getLineNumber());
    }

    public static CapturedContext.CapturedValue moduleName(Object o) {
      StackTraceElement stackTraceElement = (StackTraceElement) o;
      Object value = null;
      if (getModuleNameMethod != null) {
        try {
          value = getModuleNameMethod.invoke(stackTraceElement);
        } catch (InvocationTargetException | IllegalAccessException e) {
          throw new RuntimeException(e);
        }
      }
      return CapturedContext.CapturedValue.of("moduleName", String.class.getTypeName(), value);
    }
  }

  private static class OptionalFields {
    public static CapturedContext.CapturedValue value(Object o) {
      return CapturedContext.CapturedValue.of(
          "value", Object.class.getTypeName(), ((Optional<?>) o).orElse(null));
    }
  }
}
