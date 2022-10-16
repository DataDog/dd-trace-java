package datadog.trace.api.iast;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.HashMap;
import java.util.Map;

public class InvokeDynamicHelperRegistry {
  private static final Map<String, MethodHandle> HELPERS = new HashMap<>();

  public static void registerHelperContainer(
      MethodHandles.Lookup lookup, Class<? extends InvokeDynamicHelperContainer> container) {
    for (Method declaredMethod : container.getDeclaredMethods()) {
      InvokeDynamicHelper annotation = declaredMethod.getAnnotation(InvokeDynamicHelper.class);
      if (annotation == null) {
        continue;
      }
      try {
        registerMethod(lookup, declaredMethod);
      } catch (IllegalAccessException | NoSuchMethodException e) {
        throw new UndeclaredThrowableException(e);
      }
    }
  }

  private static void registerMethod(MethodHandles.Lookup lookup, Method method)
      throws IllegalAccessException, NoSuchMethodException {
    if (!Modifier.isStatic(method.getModifiers())) {
      throw new IllegalStateException("The helper method " + method + " should be static");
    }

    MethodHandle helperMH = lookup.unreflect(method);
    Class<?> helperClass = method.getDeclaringClass();
    String helperMethodName = method.getName();

    String helperKey = calculateHelperKey(helperClass, helperMethodName);
    if (HELPERS.containsKey(helperKey)) {
      throw new IllegalStateException(
          "Already has a helper for class " + helperClass + ", method name " + helperMethodName);
    }

    HELPERS.put(helperKey, helperMH);
  }

  private static String calculateHelperKey(Class<?> clazz, String methodName) {
    return clazz.getName() + '\0' + methodName;
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
