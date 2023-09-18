package datadog.trace.instrumentation.groovy.helper;

import datadog.trace.api.iast.csi.DynamicHelper;
import groovy.lang.GString;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Helper to perform method matching between {@link java.lang.invoke.CallSite} and {@link
 * DynamicHelper} instances
 */
public class CallSiteInvoker {

  private static final Map<Class<?>, Class<?>> BOXED_TYPES;

  static {
    BOXED_TYPES = new HashMap<>();
    BOXED_TYPES.put(boolean.class, Boolean.class);
    BOXED_TYPES.put(byte.class, Byte.class);
    BOXED_TYPES.put(char.class, Character.class);
    BOXED_TYPES.put(short.class, Short.class);
    BOXED_TYPES.put(int.class, Integer.class);
    BOXED_TYPES.put(long.class, Long.class);
    BOXED_TYPES.put(float.class, Float.class);
    BOXED_TYPES.put(double.class, Double.class);
  }

  private final DynamicHelper helper;
  private final Method callSite;
  private final Executable pointcut;
  private final boolean isStatic;
  private final boolean isCtor;
  private volatile MethodHandle handle;

  public CallSiteInvoker(final DynamicHelper helper, final Method callSite) {
    this.helper = helper;
    this.callSite = callSite;
    pointcut = findPointcut(helper);
    isStatic = Modifier.isStatic(pointcut.getModifiers());
    isCtor = pointcut instanceof Constructor;
  }

  @Override
  public String toString() {
    return String.format("CallSiteInvoker{%s -> %s}", pointcut, callSite);
  }

  /** Invoke the underlying call site using the specified arguments */
  public Object invoke(final Object... args) throws Throwable {
    if (handle == null) {
      handle = MethodHandles.publicLookup().unreflect(callSite);
    }
    return handle.invokeWithArguments(args);
  }

  /**
   * Check the signature of the {@link java.lang.invoke.CallSite} and compare it against the call
   * site
   */
  public boolean matchesSignature(final MethodType dynamicSignature) {
    // check if the signatures are compatible
    if (dynamicSignature.parameterCount() != pointcut.getParameterTypes().length + 1) {
      return false;
    }
    final Class<?>[] arguments = pointcut.getParameterTypes();
    for (int i = 0; i < arguments.length; i++) {
      if (!isAssignableFrom(arguments[i], dynamicSignature.parameterType(i + 1))) {
        return false;
      }
    }

    // static methods and constructors always start with a class
    final Class<?> owner = isCtor || isStatic ? Class.class : helper.owner();
    return isAssignableFrom(owner, dynamicSignature.parameterType(0));
  }

  /**
   * Check the arguments for static/ctors as the rest of arguments has been checked by {@link
   * CallSiteInvoker#matchesSignature(MethodType)}
   */
  public boolean matchesArguments(final Object... arguments) {
    if (isCtor || isStatic) {
      // static methods and constructors always start with a class
      final Class<?> target = (Class<?>) arguments[0];
      return isAssignableFrom(helper.owner(), target);
    }
    return true;
  }

  private static boolean isAssignableFrom(final Class<?> type, final Class<?> dynamicType) {
    final boolean assignable = type.isAssignableFrom(dynamicType);
    if (assignable) {
      return true;
    }
    // try converting the dynamic GString to a String
    if (GString.class.isAssignableFrom(dynamicType)) {
      return isAssignableFrom(type, String.class);
    }
    // try with the boxed type
    if (type.isPrimitive()) {
      return isAssignableFrom(BOXED_TYPES.get(type), dynamicType);
    }
    return false;
  }

  private static Executable findPointcut(final DynamicHelper helper) {
    try {
      return resolvePointcut(helper, helper.owner());
    } catch (final Throwable e) {
      throw new RuntimeException(
          String.format(
              "Failed to fetch executable method from %s#%s(%s)",
              helper.owner(), helper.method(), Arrays.toString(helper.argumentTypes())),
          e);
    }
  }

  private static Executable resolvePointcut(final DynamicHelper helper, final Class<?> owner) {
    try {
      if ("<init>".equals(helper.method())) {
        return owner.getDeclaredConstructor(helper.argumentTypes());
      } else {
        return owner.getDeclaredMethod(helper.method(), helper.argumentTypes());
      }
    } catch (final Throwable e) {
      throw new RuntimeException("Cannot find helper method in hierarchy");
    }
  }
}
