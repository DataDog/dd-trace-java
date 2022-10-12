package datadog.trace.api.iast;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CallSiteHelperRegistry {
  private static final Logger LOG = LoggerFactory.getLogger(CallSiteHelperRegistry.class);

  private static final Map<String, MethodHandle> HELPERS = new HashMap<>();

  public static void registerHelperContainer(
      MethodHandles.Lookup lookup, Class<? extends CallSiteHelperContainer> container) {
    for (Method declaredMethod : container.getDeclaredMethods()) {
      CallSiteHelper annotation = declaredMethod.getAnnotation(CallSiteHelper.class);
      if (annotation == null) {
        continue;
      }
      try {
        registerMethod(lookup, declaredMethod, annotation.fallbackMethodHandleProvider());
      } catch (IllegalAccessException | NoSuchMethodException e) {
        throw new UndeclaredThrowableException(e);
      }
    }
  }

  private static void registerMethod(
      MethodHandles.Lookup lookup, Method method, String fallbackMethodHandleProvider)
      throws IllegalAccessException, NoSuchMethodException {
    if (!Modifier.isStatic(method.getModifiers())) {
      throw new IllegalStateException("The helper method " + method + " should be static");
    }

    MethodHandle helperMH = lookup.unreflect(method);
    MethodHandle fallbackMH = null;
    if (fallbackMethodHandleProvider.length() > 0) {
      MethodHandle fallbackMHSupplierMH;
      try {
        fallbackMHSupplierMH =
            lookup.findStatic(
                method.getDeclaringClass(),
                fallbackMethodHandleProvider,
                MethodType.methodType(MethodHandle.class));
      } catch (IllegalAccessException | NoSuchMethodException e) {
        throw new IllegalStateException(
            "Could not find the fallback supplier method on class "
                + method.getDeclaringClass()
                + ", static method returning MethodHandle "
                + fallbackMethodHandleProvider);
      }
      try {
        fallbackMH = (MethodHandle) fallbackMHSupplierMH.invoke();
      } catch (Throwable e) {
        throw new IllegalStateException(
            "Fallback supplier method " + fallbackMethodHandleProvider + " has failed", e);
      }
    }

    registerHelper(helperMH, method.getDeclaringClass(), method.getName(), fallbackMH);
  }

  private static void registerHelper(
      MethodHandle helperMH,
      Class<?> helperClass,
      String helperMethodName,
      MethodHandle fallbackOperation) {
    String helperKey = calculateHelperKey(helperClass, helperMethodName);
    if (HELPERS.containsKey(helperKey)) {
      throw new IllegalStateException(
          "Already has a helper for class " + helperClass + ", method name " + helperMethodName);
    }
    if (fallbackOperation != null && !helperMH.type().equals(fallbackOperation.type())) {
      throw new IllegalArgumentException(
          "Type of helper method differs from that of the fallback operation: "
              + helperMH.type()
              + " and "
              + fallbackOperation.type());
    }

    MethodHandle finalMH;
    if (fallbackOperation != null) {
      finalMH = makeExceptionSafe(helperClass, helperMethodName, helperMH, fallbackOperation);
    } else {
      finalMH = makeExceptionSafe(helperClass, helperMethodName, helperMH);
    }
    HELPERS.put(helperKey, finalMH);
  }

  private static String calculateHelperKey(Class<?> clazz, String methodName) {
    return clazz.getName() + '\0' + methodName;
  }

  private static final MethodHandle GET_CAUSE_MH;
  private static final MethodHandle LOG_WARN_MH;
  private static final MethodHandle IS_REAL_CALL_THROWABLE;

  static {
    try {
      GET_CAUSE_MH =
          MethodHandles.publicLookup()
              .findVirtual(Throwable.class, "getCause", MethodType.methodType(Throwable.class));
      LOG_WARN_MH =
          MethodHandles.insertArguments(
              MethodHandles.publicLookup()
                  .findVirtual(
                      Logger.class,
                      "warn",
                      MethodType.methodType(void.class, String.class, Throwable.class)),
              0,
              LOG);
      IS_REAL_CALL_THROWABLE =
          MethodHandles.filterArguments(
              MethodHandles.insertArguments(
                  MethodHandles.publicLookup()
                      .findVirtual(
                          Class.class,
                          "isAssignableFrom",
                          MethodType.methodType(boolean.class, Class.class)),
                  0,
                  RealCallThrowable.class),
              0,
              MethodHandles.publicLookup()
                  .findVirtual(Throwable.class, "getClass", MethodType.methodType(Class.class)));

    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new UndeclaredThrowableException(e);
    }
  }

  private static MethodHandle makeExceptionSafe(
      Class<?> clazz, String methodName, MethodHandle helperMH) {
    /*
     * try {
     *   return helperMH(args...);
     * } catch (Throwable t) {
     *   LOG.warn("Call to XXX has thrown");
     *   return <default value for ret type>
     * }
     */

    String message = "Call to " + clazz.getName() + "#" + methodName + " has thrown";
    MethodHandle logMessageMH = MethodHandles.insertArguments(LOG_WARN_MH, 0, message);

    Class<?> retType = helperMH.type().returnType();
    MethodHandle handleMH;
    if (retType == void.class) {
      handleMH = MethodHandles.dropArguments(logMessageMH, 1, helperMH.type().parameterArray());
    } else {
      MethodHandle retDefValue =
          MethodHandles.dropArguments(
              MethodHandles.constant(retType, Array.get(Array.newInstance(clazz, 1), 0)),
              0,
              Throwable.class);
      handleMH = MethodHandles.foldArguments(retDefValue, logMessageMH);
      handleMH = MethodHandles.dropArguments(handleMH, 1, helperMH.type().parameterArray());
    }

    MethodHandle catchExcMH = MethodHandles.catchException(helperMH, Throwable.class, handleMH);
    return catchExcMH;
  }

  private static MethodHandle makeExceptionSafe(
      Class<?> clazz, String methodName, MethodHandle helperMH, MethodHandle fallbackOperation) {
    /*
     * try {
     *  return helperMH(args...);
     * } catch (Throwable t) {
     *   if (t instanceof RealCallThrowable) {
     *     throw t.getCause();
     *   } else {
     *     LOG.warn("Call to XXX has thrown");
     *     return fallbackOperation(args...);
     *   }
     * }
     */

    Class<?> helperRetType = helperMH.type().returnType();
    MethodHandle throwMH = MethodHandles.throwException(helperRetType, Throwable.class);
    MethodHandle throwCauseMH = MethodHandles.filterArguments(throwMH, 0, GET_CAUSE_MH);

    String message = "Call to " + clazz.getName() + "#" + methodName + " has thrown";
    MethodHandle logMessageMH = MethodHandles.insertArguments(LOG_WARN_MH, 0, message);
    MethodHandle remThrFromFallbackMH =
        MethodHandles.dropArguments(fallbackOperation, 0, Throwable.class);
    MethodHandle logAndCallFallbackMH =
        MethodHandles.foldArguments(remThrFromFallbackMH, logMessageMH);

    MethodHandle handleExcMH =
        MethodHandles.guardWithTest(
            MethodHandles.dropArguments(
                IS_REAL_CALL_THROWABLE, 1, helperMH.type().parameterArray()),
            MethodHandles.dropArguments(throwCauseMH, 1, helperMH.type().parameterArray()),
            logAndCallFallbackMH);
    MethodHandle catchExcMH = MethodHandles.catchException(helperMH, Throwable.class, handleExcMH);

    return catchExcMH;
  }

  public static CallSite bootstrap(
      MethodHandles.Lookup lookup, String name, MethodType methodType, String helperKey) {
    MethodHandle methodHandle = HELPERS.get(helperKey);
    if (methodHandle == null) {
      throw new IllegalStateException("Helper with key " + helperKey + " is not registered");
    }
    if (!methodType.equals(methodHandle.type())) {
      throw new IllegalStateException(
          "Mismatch of types. Helper has type "
              + methodHandle.type()
              + ", whereas expected type at call site is "
              + methodType);
    }

    return new ConstantCallSite(methodHandle);
  }

  // for testing
  public static void reset() {
    HELPERS.clear();
  }
}
