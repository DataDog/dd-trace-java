package datadog.trace.bootstrap.debugger.util;

import static datadog.trace.api.telemetry.LogCollector.EXCLUDE_TELEMETRY;
import static java.lang.invoke.MethodType.methodType;

import datadog.environment.JavaVirtualMachine;
import datadog.trace.bootstrap.debugger.CapturedContext;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.ToLongFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WellKnownClasses {
  private static final Logger LOGGER = LoggerFactory.getLogger(WellKnownClasses.class);

  /** Set of class names which have a toString side effect free and class final */
  private static final Map<String, Function<Object, String>> TO_STRING_FINAL_SAFE_CLASSES =
      new HashMap<>();

  static {
    TO_STRING_FINAL_SAFE_CLASSES.put("java.lang.Class", WellKnownClasses::classToString);
    TO_STRING_FINAL_SAFE_CLASSES.put("java.lang.String", String::valueOf);
    TO_STRING_FINAL_SAFE_CLASSES.put("java.lang.Boolean", String::valueOf);
    TO_STRING_FINAL_SAFE_CLASSES.put("java.lang.Integer", String::valueOf);
    TO_STRING_FINAL_SAFE_CLASSES.put("java.lang.Long", String::valueOf);
    TO_STRING_FINAL_SAFE_CLASSES.put("java.lang.Double", String::valueOf);
    TO_STRING_FINAL_SAFE_CLASSES.put("java.lang.Character", String::valueOf);
    TO_STRING_FINAL_SAFE_CLASSES.put("java.lang.Byte", String::valueOf);
    TO_STRING_FINAL_SAFE_CLASSES.put("java.lang.Float", String::valueOf);
    TO_STRING_FINAL_SAFE_CLASSES.put("java.lang.Short", String::valueOf);
    TO_STRING_FINAL_SAFE_CLASSES.put("java.math.BigDecimal", String::valueOf);
    TO_STRING_FINAL_SAFE_CLASSES.put("java.math.BigInteger", String::valueOf);
    TO_STRING_FINAL_SAFE_CLASSES.put("java.time.Duration", String::valueOf);
    TO_STRING_FINAL_SAFE_CLASSES.put("java.time.Instant", String::valueOf);
    TO_STRING_FINAL_SAFE_CLASSES.put("java.time.LocalTime", String::valueOf);
    TO_STRING_FINAL_SAFE_CLASSES.put("java.time.LocalDate", String::valueOf);
    TO_STRING_FINAL_SAFE_CLASSES.put("java.time.LocalDateTime", String::valueOf);
    TO_STRING_FINAL_SAFE_CLASSES.put("java.util.UUID", String::valueOf);
    TO_STRING_FINAL_SAFE_CLASSES.put("java.net.URI", String::valueOf);
  }

  private static final Map<String, Function<Object, String>> SAFE_TO_STRING_FUNCTIONS =
      new HashMap<>();

  static {
    SAFE_TO_STRING_FUNCTIONS.putAll(TO_STRING_FINAL_SAFE_CLASSES);
    SAFE_TO_STRING_FUNCTIONS.put("java.util.concurrent.atomic.AtomicBoolean", String::valueOf);
    SAFE_TO_STRING_FUNCTIONS.put("java.util.concurrent.atomic.AtomicInteger", String::valueOf);
    SAFE_TO_STRING_FUNCTIONS.put("java.util.concurrent.atomic.AtomicLong", String::valueOf);
    SAFE_TO_STRING_FUNCTIONS.put("java.io.File", String::valueOf);
    // implementations of java.io.file.Path interfaces
    SAFE_TO_STRING_FUNCTIONS.put("sun.nio.fs.UnixPath", String::valueOf);
    SAFE_TO_STRING_FUNCTIONS.put("sun.nio.fs.WindowsPath", String::valueOf);
    SAFE_TO_STRING_FUNCTIONS.put("java.util.Date", WellKnownClasses::dateToString);
  }

  private static final Map<String, ToLongFunction<Object>> LONG_FUNCTIONS = new HashMap<>();

  static {
    LONG_FUNCTIONS.put("java.util.Date", WellKnownClasses::dateToLongValue);
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
    EQUALS_SAFE_CLASSES.add("java.io.File");
    EQUALS_SAFE_CLASSES.add("sun.nio.fs.UnixPath");
    EQUALS_SAFE_CLASSES.add("sun.nio.fs.WindowsPath");
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
              "java.util.UUID",
              "java.net.URI",
              "java.io.File",
              "sun.nio.fs.UnixPath",
              "sun.nio.fs.WindowsPath"));

  private static final Set<String> LONG_PRIMITIVES = new HashSet<>(Arrays.asList("java.util.Date"));

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
  private static final Map<String, Function<Object, CapturedContext.CapturedValue>>
      OPTIONALINT_SPECIAL_FIELDS = new HashMap<>();
  private static final Map<String, Function<Object, CapturedContext.CapturedValue>>
      OPTIONALDOUBLE_SPECIAL_FIELDS = new HashMap<>();
  private static final Map<String, Function<Object, CapturedContext.CapturedValue>>
      OPTIONALLONG_SPECIAL_FIELDS = new HashMap<>();
  private static final Map<String, Function<Object, CapturedContext.CapturedValue>>
      COMPLETABLEFUTURE_SPECIAL_FIELDS = new HashMap<>();

  static {
    OPTIONAL_SPECIAL_FIELDS.put("value", OptionalFields::value);
    OPTIONALINT_SPECIAL_FIELDS.put("value", OptionalFields::valueInt);
    OPTIONALDOUBLE_SPECIAL_FIELDS.put("value", OptionalFields::valueDouble);
    OPTIONALLONG_SPECIAL_FIELDS.put("value", OptionalFields::valueLong);
    if (JavaVirtualMachine.isJavaVersionAtLeast(19)) {
      // Future::resultNow method is available since JDK 19
      COMPLETABLEFUTURE_SPECIAL_FIELDS.put("result", CompletableFutureFields::result);
    }
  }

  static {
    SPECIAL_TYPE_ACCESS.put(StackTraceElement.class, STACKTRACEELEMENT_SPECIAL_FIELDS);
    SPECIAL_TYPE_ACCESS.put(Optional.class, OPTIONAL_SPECIAL_FIELDS);
    SPECIAL_TYPE_ACCESS.put(OptionalInt.class, OPTIONALINT_SPECIAL_FIELDS);
    SPECIAL_TYPE_ACCESS.put(OptionalDouble.class, OPTIONALDOUBLE_SPECIAL_FIELDS);
    SPECIAL_TYPE_ACCESS.put(OptionalLong.class, OPTIONALLONG_SPECIAL_FIELDS);
    if (JavaVirtualMachine.isJavaVersionAtLeast(19)) {
      // Future::resultNow method is available since JDK 19
      SPECIAL_TYPE_ACCESS.put(CompletableFuture.class, COMPLETABLEFUTURE_SPECIAL_FIELDS);
    }
  }

  private static final Map<String, Function<Object, CapturedContext.CapturedValue>>
      THROWABLE_SPECIAL_FIELDS = new HashMap<>();

  static {
    THROWABLE_SPECIAL_FIELDS.put("detailMessage", ThrowableFields::detailMessage);
    THROWABLE_SPECIAL_FIELDS.put("suppressedExceptions", ThrowableFields::suppressedExceptions);
    THROWABLE_SPECIAL_FIELDS.put("stackTrace", ThrowableFields::stackTrace);
    THROWABLE_SPECIAL_FIELDS.put("cause", ThrowableFields::cause);
  }

  private static final List<String> SAFE_COLLECTION_PACKAGES =
      Arrays.asList(
          "java.", // JDK base module
          "com.google.protobuf.", // Google ProtoBuf
          "com.google.common.collect.", // Google Guava
          "it.unimi.dsi.fastutil.", // fastutil
          "org.agrona.collections." // Agrona
          );

  private static final List<String> SAFE_MAP_PACKAGES =
      Arrays.asList(
          "java.", // JDK base module
          "com.google.protobuf.", // Google ProtoBuf
          "com.google.common.collect.", // Google Guava
          "it.unimi.dsi.fastutil.", // fastutil
          "org.agrona.collections." // Agrona
          );

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

  /**
   * @return true if collection implementation is safe to call (only in-memory)
   */
  public static boolean isSafe(Collection<?> collection) {
    String className = collection.getClass().getTypeName();
    for (String safePackage : SAFE_COLLECTION_PACKAGES) {
      if (className.startsWith(safePackage)) {
        return true;
      }
    }
    return false;
  }

  /**
   * @return true if map implementation is safe to call (only in-memory)
   */
  public static boolean isSafe(Map<?, ?> map) {
    String className = map.getClass().getTypeName();
    for (String safePackage : SAFE_MAP_PACKAGES) {
      if (className.startsWith(safePackage)) {
        return true;
      }
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
   * indicates if type is considered as a int/long primitive and can be compared to another long
   * value or literal with Expression Language
   */
  public static boolean isLongPrimitive(String type) {
    return LONG_PRIMITIVES.contains(type);
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
   * @param type the type name of the object to generate a string representation for. Must be a
   *     concrete type, not a declared type. see {@link #isToStringSafe(String)}
   * @return a function to generate a string representation of a type where the default toString
   *     method is not suitable
   */
  public static Function<Object, String> getSafeToString(String type) {
    return SAFE_TO_STRING_FUNCTIONS.get(type);
  }

  private static String classToString(Object o) {
    return ((Class<?>) o).getTypeName();
  }

  private static String dateToString(Object o) {
    return Long.toString(((Date) o).getTime());
  }

  private static long dateToLongValue(Object o) {
    return ((Date) o).getTime();
  }

  public static boolean isEqualsSafe(Class<?> clazz) {
    return clazz.isPrimitive()
        || clazz.isEnum()
        || EQUALS_SAFE_CLASSES.contains(clazz.getTypeName());
  }

  public static ToLongFunction<Object> getLongPrimitiveValueFunction(String typeName) {
    return LONG_FUNCTIONS.get(typeName);
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

    public static CapturedContext.CapturedValue valueInt(Object o) {
      return CapturedContext.CapturedValue.of(
          "value", Integer.TYPE.getTypeName(), ((OptionalInt) o).orElse(0));
    }

    public static CapturedContext.CapturedValue valueDouble(Object o) {
      return CapturedContext.CapturedValue.of(
          "value", Double.TYPE.getTypeName(), ((OptionalDouble) o).orElse(0.0));
    }

    public static CapturedContext.CapturedValue valueLong(Object o) {
      return CapturedContext.CapturedValue.of(
          "value", Long.TYPE.getTypeName(), ((OptionalLong) o).orElse(0L));
    }
  }

  private static class CompletableFutureFields {
    private static final MethodHandle RESULT_NOW;

    static {
      MethodHandle methodHandle = null;
      try {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        methodHandle =
            lookup.findVirtual(CompletableFuture.class, "resultNow", methodType(Object.class));
      } catch (Exception e) {
        LOGGER.debug(EXCLUDE_TELEMETRY, "Looking up CompletableFuture::resultNow failed: ", e);
      }
      RESULT_NOW = methodHandle;
    }

    public static CapturedContext.CapturedValue result(Object o) {
      if (RESULT_NOW == null) {
        throw new UnsupportedOperationException("CompletableFuture::resultNow not available");
      }
      try {
        CompletableFuture<?> future = (CompletableFuture<?>) o;
        // need to check with isDone() to avoid getting exception if null.
        // Known benign rare race condition result != null => result == null
        // between isDone() and resultNow()
        Object result = future.isDone() ? RESULT_NOW.invokeExact((future)) : null;
        return CapturedContext.CapturedValue.of("result", Object.class.getTypeName(), result);
      } catch (Throwable t) {
        throw new RuntimeException(t);
      }
    }
  }
}
