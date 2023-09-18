package datadog.trace.instrumentation.groovy;

import static datadog.trace.instrumentation.groovy.helper.InvocationHelper.INIT;
import static datadog.trace.instrumentation.groovy.helper.InvocationHelper.INIT_TYPE;
import static datadog.trace.instrumentation.groovy.helper.InvocationHelper.INVOKE_TYPE;
import static datadog.trace.instrumentation.groovy.helper.InvocationHelper.adaptMethodHandle;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.csi.SkipDynamicHelpers;
import datadog.trace.instrumentation.groovy.helper.CallSiteInvoker;
import datadog.trace.instrumentation.groovy.helper.InvocationHelper;
import datadog.trace.util.stacktrace.StackUtils;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class adapts calls to {@link org.codehaus.groovy.runtime.callsite.CallSite} to append calls
 * to our IAST call sites
 */
@SuppressWarnings({"unused", "DataFlowIssue"})
@Propagation
@CallSite(
    spi = IastCallSites.class,
    helpers = {InvocationHelper.class, CallSiteInvoker.class},
    enabled = {"datadog.trace.api.iast.IastEnabledChecks", "isGroovyIndyEnabled"})
@SkipDynamicHelpers
public class GroovyCallSiteCallSite {

  private static final Object NOT_FOUND = new Object();
  private static final MethodType INSTANCE =
      MethodType.methodType(Object.class, Object.class, Object[].class);
  private static final MethodType STATIC =
      MethodType.methodType(Object.class, Class.class, Object[].class);
  private static final MethodType CTOR =
      MethodType.methodType(Object.class, Object.class, Object[].class);

  private static final Logger LOG = LoggerFactory.getLogger(ScriptBytecodeAdapterCallSite.class);

  private static final Map<String, Map<MethodType, MethodHandle>> CACHE = new ConcurrentHashMap<>();

  @CallSite.Around(
      "java.lang.Object org.codehaus.groovy.runtime.callsite.CallSite.callStatic(java.lang.Class, java.lang.Object[])")
  public static Object callStatic(
      @CallSite.This final org.codehaus.groovy.runtime.callsite.CallSite callSite,
      @CallSite.Argument final Class<?> receiver,
      @CallSite.Argument final Object[] arguments)
      throws Throwable {
    if (callSite != null && InvocationHelper.supports(callSite.getName())) {
      final String method = callSite.getName();
      final Object result =
          internalCall(callSite, "callStatic", STATIC, INVOKE_TYPE, method, receiver, arguments);
      if (result != NOT_FOUND) {
        return result;
      }
    }
    try {
      return callSite.callStatic(receiver, arguments);
    } catch (final Throwable e) {
      throw StackUtils.filterDatadog(e);
    }
  }

  @CallSite.Around(
      "java.lang.Object org.codehaus.groovy.runtime.callsite.CallSite.callStatic(java.lang.Class)")
  public static Object call(
      @CallSite.This final org.codehaus.groovy.runtime.callsite.CallSite callSite,
      @CallSite.Argument final Class<?> receiver)
      throws Throwable {
    if (callSite != null && InvocationHelper.supports(callSite.getName())) {
      final String method = callSite.getName();
      final Object result =
          internalCall(callSite, "callStatic", STATIC, INVOKE_TYPE, method, receiver);
      if (result != NOT_FOUND) {
        return result;
      }
    }
    try {
      return callSite.callStatic(receiver);
    } catch (final Throwable e) {
      throw StackUtils.filterDatadog(e);
    }
  }

  @CallSite.Around(
      "java.lang.Object org.codehaus.groovy.runtime.callsite.CallSite.callStatic(java.lang.Class, java.lang.Object)")
  public static Object callStatic(
      @CallSite.This final org.codehaus.groovy.runtime.callsite.CallSite callSite,
      @CallSite.Argument final Class<?> receiver,
      @CallSite.Argument final Object arg1)
      throws Throwable {
    if (callSite != null && InvocationHelper.supports(callSite.getName())) {
      final String method = callSite.getName();
      final Object result =
          internalCall(callSite, "callStatic", STATIC, INVOKE_TYPE, method, receiver, arg1);
      if (result != NOT_FOUND) {
        return result;
      }
    }
    try {
      return callSite.callStatic(receiver, arg1);
    } catch (final Throwable e) {
      throw StackUtils.filterDatadog(e);
    }
  }

  @CallSite.Around(
      "java.lang.Object org.codehaus.groovy.runtime.callsite.CallSite.callStatic(java.lang.Class, java.lang.Object, java.lang.Object)")
  public static Object callStatic(
      @CallSite.This final org.codehaus.groovy.runtime.callsite.CallSite callSite,
      @CallSite.Argument final Class<?> receiver,
      @CallSite.Argument final Object arg1,
      @CallSite.Argument final Object arg2)
      throws Throwable {
    if (callSite != null && InvocationHelper.supports(callSite.getName())) {
      final String method = callSite.getName();
      final Object result =
          internalCall(callSite, "callStatic", STATIC, INVOKE_TYPE, method, receiver, arg1, arg2);
      if (result != NOT_FOUND) {
        return result;
      }
    }
    try {
      return callSite.callStatic(receiver, arg1, arg2);
    } catch (final Throwable e) {
      throw StackUtils.filterDatadog(e);
    }
  }

  @CallSite.Around(
      "java.lang.Object org.codehaus.groovy.runtime.callsite.CallSite.callStatic(java.lang.Class, java.lang.Object, java.lang.Object, java.lang.Object)")
  public static Object callStatic(
      @CallSite.This final org.codehaus.groovy.runtime.callsite.CallSite callSite,
      @CallSite.Argument final Class<?> receiver,
      @CallSite.Argument final Object arg1,
      @CallSite.Argument final Object arg2,
      @CallSite.Argument final Object arg3)
      throws Throwable {
    if (callSite != null && InvocationHelper.supports(callSite.getName())) {
      final String method = callSite.getName();
      final Object result =
          internalCall(
              callSite, "callStatic", STATIC, INVOKE_TYPE, method, receiver, arg1, arg2, arg3);
      if (result != NOT_FOUND) {
        return result;
      }
    }
    try {
      return callSite.callStatic(receiver, arg1, arg2, arg3);
    } catch (final Throwable e) {
      throw StackUtils.filterDatadog(e);
    }
  }

  @CallSite.Around(
      "java.lang.Object org.codehaus.groovy.runtime.callsite.CallSite.callStatic(java.lang.Class, java.lang.Object, java.lang.Object, java.lang.Object, java.lang.Object)")
  public static Object call(
      @CallSite.This final org.codehaus.groovy.runtime.callsite.CallSite callSite,
      @CallSite.Argument final Class<?> receiver,
      @CallSite.Argument final Object arg1,
      @CallSite.Argument final Object arg2,
      @CallSite.Argument final Object arg3,
      @CallSite.Argument final Object arg4)
      throws Throwable {
    if (callSite != null && InvocationHelper.supports(callSite.getName())) {
      final String method = callSite.getName();
      final Object result =
          internalCall(
              callSite,
              "callStatic",
              STATIC,
              INVOKE_TYPE,
              method,
              receiver,
              arg1,
              arg2,
              arg3,
              arg4);
      if (result != NOT_FOUND) {
        return result;
      }
    }
    try {
      return callSite.callStatic(receiver, arg1, arg2, arg3, arg4);
    } catch (final Throwable e) {
      throw StackUtils.filterDatadog(e);
    }
  }

  @CallSite.Around(
      "java.lang.Object org.codehaus.groovy.runtime.callsite.CallSite.call(java.lang.Object, java.lang.Object[])")
  public static Object call(
      @CallSite.This final org.codehaus.groovy.runtime.callsite.CallSite callSite,
      @CallSite.Argument final Object receiver,
      @CallSite.Argument final Object[] arguments)
      throws Throwable {
    if (callSite != null && InvocationHelper.supports(callSite.getName())) {
      final String method = callSite.getName();
      final Object result =
          internalCall(callSite, "call", INSTANCE, INVOKE_TYPE, method, receiver, arguments);
      if (result != NOT_FOUND) {
        return result;
      }
    }
    try {
      return callSite.call(receiver, arguments);
    } catch (final Throwable e) {
      throw StackUtils.filterDatadog(e);
    }
  }

  @CallSite.Around(
      "java.lang.Object org.codehaus.groovy.runtime.callsite.CallSite.call(java.lang.Object)")
  public static Object call(
      @CallSite.This final org.codehaus.groovy.runtime.callsite.CallSite callSite,
      @CallSite.Argument final Object receiver)
      throws Throwable {
    if (callSite != null && InvocationHelper.supports(callSite.getName())) {
      final String method = callSite.getName();
      final Object result = internalCall(callSite, "call", INSTANCE, INVOKE_TYPE, method, receiver);
      if (result != NOT_FOUND) {
        return result;
      }
    }
    try {
      return callSite.call(receiver);
    } catch (final Throwable e) {
      throw StackUtils.filterDatadog(e);
    }
  }

  @CallSite.Around(
      "java.lang.Object org.codehaus.groovy.runtime.callsite.CallSite.call(java.lang.Object, java.lang.Object)")
  public static Object call(
      @CallSite.This final org.codehaus.groovy.runtime.callsite.CallSite callSite,
      @CallSite.Argument final Object receiver,
      @CallSite.Argument final Object arg1)
      throws Throwable {
    if (callSite != null && InvocationHelper.supports(callSite.getName())) {
      final String method = callSite.getName();
      final Object result =
          internalCall(callSite, "call", INSTANCE, INVOKE_TYPE, method, receiver, arg1);
      if (result != NOT_FOUND) {
        return result;
      }
    }
    try {
      return callSite.call(receiver, arg1);
    } catch (final Throwable e) {
      throw StackUtils.filterDatadog(e);
    }
  }

  @CallSite.Around(
      "java.lang.Object org.codehaus.groovy.runtime.callsite.CallSite.call(java.lang.Object, java.lang.Object, java.lang.Object)")
  public static Object call(
      @CallSite.This final org.codehaus.groovy.runtime.callsite.CallSite callSite,
      @CallSite.Argument final Object receiver,
      @CallSite.Argument final Object arg1,
      @CallSite.Argument final Object arg2)
      throws Throwable {
    if (callSite != null && InvocationHelper.supports(callSite.getName())) {
      final String method = callSite.getName();
      final Object result =
          internalCall(callSite, "call", INSTANCE, INVOKE_TYPE, method, receiver, arg1, arg2);
      if (result != NOT_FOUND) {
        return result;
      }
    }
    try {
      return callSite.call(receiver, arg1, arg2);
    } catch (final Throwable e) {
      throw StackUtils.filterDatadog(e);
    }
  }

  @CallSite.Around(
      "java.lang.Object org.codehaus.groovy.runtime.callsite.CallSite.call(java.lang.Object, java.lang.Object, java.lang.Object, java.lang.Object)")
  public static Object call(
      @CallSite.This final org.codehaus.groovy.runtime.callsite.CallSite callSite,
      @CallSite.Argument final Object receiver,
      @CallSite.Argument final Object arg1,
      @CallSite.Argument final Object arg2,
      @CallSite.Argument final Object arg3)
      throws Throwable {
    if (callSite != null && InvocationHelper.supports(callSite.getName())) {
      final String method = callSite.getName();
      final Object result =
          internalCall(callSite, "call", INSTANCE, INVOKE_TYPE, method, receiver, arg1, arg2, arg3);
      if (result != NOT_FOUND) {
        return result;
      }
    }
    try {
      return callSite.call(receiver, arg1, arg2, arg3);
    } catch (final Throwable e) {
      throw StackUtils.filterDatadog(e);
    }
  }

  @CallSite.Around(
      "java.lang.Object org.codehaus.groovy.runtime.callsite.CallSite.call(java.lang.Object, java.lang.Object, java.lang.Object, java.lang.Object, java.lang.Object)")
  public static Object call(
      @CallSite.This final org.codehaus.groovy.runtime.callsite.CallSite callSite,
      @CallSite.Argument final Object receiver,
      @CallSite.Argument final Object arg1,
      @CallSite.Argument final Object arg2,
      @CallSite.Argument final Object arg3,
      @CallSite.Argument final Object arg4)
      throws Throwable {
    if (callSite != null && InvocationHelper.supports(callSite.getName())) {
      final String method = callSite.getName();
      final Object result =
          internalCall(
              callSite, "call", INSTANCE, INVOKE_TYPE, method, receiver, arg1, arg2, arg3, arg4);
      if (result != NOT_FOUND) {
        return result;
      }
    }
    try {
      return callSite.call(receiver, arg1, arg2, arg3, arg4);
    } catch (final Throwable e) {
      throw StackUtils.filterDatadog(e);
    }
  }

  @CallSite.Around(
      "java.lang.Object org.codehaus.groovy.runtime.callsite.CallSite.callSafe(java.lang.Object, java.lang.Object[])")
  public static Object callSafe(
      @CallSite.This final org.codehaus.groovy.runtime.callsite.CallSite callSite,
      @CallSite.Argument final Object receiver,
      @CallSite.Argument final Object[] arguments)
      throws Throwable {
    if (callSite != null && InvocationHelper.supports(callSite.getName())) {
      final String method = callSite.getName();
      final Object result =
          internalCall(callSite, "callSafe", INSTANCE, INVOKE_TYPE, method, receiver, arguments);
      if (result != NOT_FOUND) {
        return result;
      }
    }
    try {
      return callSite.callSafe(receiver, arguments);
    } catch (final Throwable e) {
      throw StackUtils.filterDatadog(e);
    }
  }

  @CallSite.Around(
      "java.lang.Object org.codehaus.groovy.runtime.callsite.CallSite.callSafe(java.lang.Object)")
  public static Object callSafe(
      @CallSite.This final org.codehaus.groovy.runtime.callsite.CallSite callSite,
      @CallSite.Argument final Object receiver)
      throws Throwable {
    if (callSite != null && InvocationHelper.supports(callSite.getName())) {
      final String method = callSite.getName();
      final Object result =
          internalCall(callSite, "callSafe", INSTANCE, INVOKE_TYPE, method, receiver);
      if (result != NOT_FOUND) {
        return result;
      }
    }
    try {
      return callSite.callSafe(receiver);
    } catch (final Throwable e) {
      throw StackUtils.filterDatadog(e);
    }
  }

  @CallSite.Around(
      "java.lang.Object org.codehaus.groovy.runtime.callsite.CallSite.callSafe(java.lang.Object, java.lang.Object)")
  public static Object callSafe(
      @CallSite.This final org.codehaus.groovy.runtime.callsite.CallSite callSite,
      @CallSite.Argument final Object receiver,
      @CallSite.Argument final Object arg1)
      throws Throwable {
    if (callSite != null && InvocationHelper.supports(callSite.getName())) {
      final String method = callSite.getName();
      final Object result =
          internalCall(callSite, "callSafe", INSTANCE, INVOKE_TYPE, method, receiver, arg1);
      if (result != NOT_FOUND) {
        return result;
      }
    }
    try {
      return callSite.callSafe(receiver, arg1);
    } catch (final Throwable e) {
      throw StackUtils.filterDatadog(e);
    }
  }

  @CallSite.Around(
      "java.lang.Object org.codehaus.groovy.runtime.callsite.CallSite.callSafe(java.lang.Object, java.lang.Object, java.lang.Object)")
  public static Object callSafe(
      @CallSite.This final org.codehaus.groovy.runtime.callsite.CallSite callSite,
      @CallSite.Argument final Object receiver,
      @CallSite.Argument final Object arg1,
      @CallSite.Argument final Object arg2)
      throws Throwable {
    if (callSite != null && InvocationHelper.supports(callSite.getName())) {
      final String method = callSite.getName();
      final Object result =
          internalCall(callSite, "callSafe", INSTANCE, INVOKE_TYPE, method, receiver, arg1, arg2);
      if (result != NOT_FOUND) {
        return result;
      }
    }
    try {
      return callSite.callSafe(receiver, arg1, arg2);
    } catch (final Throwable e) {
      throw StackUtils.filterDatadog(e);
    }
  }

  @CallSite.Around(
      "java.lang.Object org.codehaus.groovy.runtime.callsite.CallSite.callSafe(java.lang.Object, java.lang.Object, java.lang.Object, java.lang.Object)")
  public static Object callSafe(
      @CallSite.This final org.codehaus.groovy.runtime.callsite.CallSite callSite,
      @CallSite.Argument final Object receiver,
      @CallSite.Argument final Object arg1,
      @CallSite.Argument final Object arg2,
      @CallSite.Argument final Object arg3)
      throws Throwable {
    if (callSite != null && InvocationHelper.supports(callSite.getName())) {
      final String method = callSite.getName();
      final Object result =
          internalCall(
              callSite, "callSafe", INSTANCE, INVOKE_TYPE, method, receiver, arg1, arg2, arg3);
      if (result != NOT_FOUND) {
        return result;
      }
    }
    try {
      return callSite.callSafe(receiver, arg1, arg2, arg3);
    } catch (final Throwable e) {
      throw StackUtils.filterDatadog(e);
    }
  }

  @CallSite.Around(
      "java.lang.Object org.codehaus.groovy.runtime.callsite.CallSite.callSafe(java.lang.Object, java.lang.Object, java.lang.Object, java.lang.Object, java.lang.Object)")
  public static Object callSafe(
      @CallSite.This final org.codehaus.groovy.runtime.callsite.CallSite callSite,
      @CallSite.Argument final Object receiver,
      @CallSite.Argument final Object arg1,
      @CallSite.Argument final Object arg2,
      @CallSite.Argument final Object arg3,
      @CallSite.Argument final Object arg4)
      throws Throwable {
    if (callSite != null && InvocationHelper.supports(callSite.getName())) {
      final String method = callSite.getName();
      final Object result =
          internalCall(
              callSite,
              "callSafe",
              INSTANCE,
              INVOKE_TYPE,
              method,
              receiver,
              arg1,
              arg2,
              arg3,
              arg4);
      if (result != NOT_FOUND) {
        return result;
      }
    }
    try {
      return callSite.callSafe(receiver, arg1, arg2, arg3, arg4);
    } catch (final Throwable e) {
      throw StackUtils.filterDatadog(e);
    }
  }

  @CallSite.Around(
      "java.lang.Object org.codehaus.groovy.runtime.callsite.CallSite.callConstructor(java.lang.Object, java.lang.Object[])")
  public static Object callConstructor(
      @CallSite.This final org.codehaus.groovy.runtime.callsite.CallSite callSite,
      @CallSite.Argument final Object receiver,
      @CallSite.Argument final Object[] arguments)
      throws Throwable {
    if (callSite != null && InvocationHelper.supports(INIT)) {
      final Object result =
          internalCall(callSite, "callConstructor", CTOR, INIT_TYPE, INIT, receiver, arguments);
      if (result != NOT_FOUND) {
        return result;
      }
    }
    try {
      return callSite.callConstructor(receiver, arguments);
    } catch (final Throwable e) {
      throw StackUtils.filterDatadog(e);
    }
  }

  @CallSite.Around(
      "java.lang.Object org.codehaus.groovy.runtime.callsite.CallSite.callConstructor(java.lang.Object)")
  public static Object callConstructor(
      @CallSite.This final org.codehaus.groovy.runtime.callsite.CallSite callSite,
      @CallSite.Argument final Object receiver)
      throws Throwable {
    if (callSite != null && InvocationHelper.supports(INIT)) {
      final Object result =
          internalCall(callSite, "callConstructor", CTOR, INIT_TYPE, INIT, receiver);
      if (result != NOT_FOUND) {
        return result;
      }
    }
    try {
      return callSite.callConstructor(receiver);
    } catch (final Throwable e) {
      throw StackUtils.filterDatadog(e);
    }
  }

  @CallSite.Around(
      "java.lang.Object org.codehaus.groovy.runtime.callsite.CallSite.callConstructor(java.lang.Object, java.lang.Object)")
  public static Object callConstructor(
      @CallSite.This final org.codehaus.groovy.runtime.callsite.CallSite callSite,
      @CallSite.Argument final Object receiver,
      @CallSite.Argument final Object arg1)
      throws Throwable {
    if (callSite != null && InvocationHelper.supports(INIT)) {
      final Object result =
          internalCall(callSite, "callConstructor", CTOR, INIT_TYPE, INIT, receiver, arg1);
      if (result != NOT_FOUND) {
        return result;
      }
    }
    try {
      return callSite.callConstructor(receiver, arg1);
    } catch (final Throwable e) {
      throw StackUtils.filterDatadog(e);
    }
  }

  @CallSite.Around(
      "java.lang.Object org.codehaus.groovy.runtime.callsite.CallSite.callConstructor(java.lang.Object, java.lang.Object, java.lang.Object)")
  public static Object callConstructor(
      @CallSite.This final org.codehaus.groovy.runtime.callsite.CallSite callSite,
      @CallSite.Argument final Object receiver,
      @CallSite.Argument final Object arg1,
      @CallSite.Argument final Object arg2)
      throws Throwable {
    if (callSite != null && InvocationHelper.supports(INIT)) {
      final Object result =
          internalCall(callSite, "callConstructor", CTOR, INIT_TYPE, INIT, receiver, arg1, arg2);
      if (result != NOT_FOUND) {
        return result;
      }
    }
    try {
      return callSite.callConstructor(receiver, arg1, arg2);
    } catch (final Throwable e) {
      throw StackUtils.filterDatadog(e);
    }
  }

  @CallSite.Around(
      "java.lang.Object org.codehaus.groovy.runtime.callsite.CallSite.callConstructor(java.lang.Object, java.lang.Object, java.lang.Object, java.lang.Object)")
  public static Object callConstructor(
      @CallSite.This final org.codehaus.groovy.runtime.callsite.CallSite callSite,
      @CallSite.Argument final Object receiver,
      @CallSite.Argument final Object arg1,
      @CallSite.Argument final Object arg2,
      @CallSite.Argument final Object arg3)
      throws Throwable {
    if (callSite != null && InvocationHelper.supports(INIT)) {
      final Object result =
          internalCall(
              callSite, "callConstructor", CTOR, INIT_TYPE, INIT, receiver, arg1, arg2, arg3);
      if (result != NOT_FOUND) {
        return result;
      }
    }
    try {
      return callSite.callConstructor(receiver, arg1, arg2, arg3);
    } catch (final Throwable e) {
      throw StackUtils.filterDatadog(e);
    }
  }

  @CallSite.Around(
      "java.lang.Object org.codehaus.groovy.runtime.callsite.CallSite.callConstructor(java.lang.Object, java.lang.Object, java.lang.Object, java.lang.Object, java.lang.Object)")
  public static Object callConstructor(
      @CallSite.This final org.codehaus.groovy.runtime.callsite.CallSite callSite,
      @CallSite.Argument final Object receiver,
      @CallSite.Argument final Object arg1,
      @CallSite.Argument final Object arg2,
      @CallSite.Argument final Object arg3,
      @CallSite.Argument final Object arg4)
      throws Throwable {
    if (callSite != null && InvocationHelper.supports(INIT)) {
      final Object result =
          internalCall(
              callSite, "callConstructor", CTOR, INIT_TYPE, INIT, receiver, arg1, arg2, arg3, arg4);
      if (result != NOT_FOUND) {
        return result;
      }
    }
    try {
      return callSite.callConstructor(receiver, arg1, arg2, arg3, arg4);
    } catch (final Throwable e) {
      throw StackUtils.filterDatadog(e);
    }
  }

  /** Calls the groovy call site using the (Object, Object[])Object variant */
  private static Object internalCall(
      final org.codehaus.groovy.runtime.callsite.CallSite callSite,
      final String callSiteMethodName,
      final MethodType callSiteMethodType,
      final String callType,
      final String method,
      final Object receiver,
      final Object... arguments) {
    try {
      final MethodHandle target = resolveMethod(callSiteMethodName, callSiteMethodType, callSite);
      final MethodHandle invoke = target.asCollector(Object[].class, arguments.length);
      final MethodHandle adapter = adaptMethodHandle(callType, method, invoke, receiver, arguments);
      if (adapter != null) {
        return adapter.invokeWithArguments(flatten(receiver, arguments));
      }
    } catch (final Throwable e) {
      LOG.error("Error on {} handler", callSiteMethodName, e);
    }
    return NOT_FOUND;
  }

  private static MethodHandle resolveMethod(
      final String scriptMethod,
      final MethodType methodType,
      final org.codehaus.groovy.runtime.callsite.CallSite callSite) {
    final Map<MethodType, MethodHandle> map =
        CACHE.computeIfAbsent(scriptMethod, s -> new ConcurrentHashMap<>());
    final MethodHandle handle =
        map.computeIfAbsent(
            methodType,
            t -> {
              try {
                return MethodHandles.publicLookup()
                    .findVirtual(
                        org.codehaus.groovy.runtime.callsite.CallSite.class,
                        scriptMethod,
                        methodType);
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            });
    return handle == null ? null : handle.bindTo(callSite);
  }

  private static Object[] flatten(final Object element, final Object[] args) {
    final Object[] result = new Object[args.length + 1];
    result[0] = element;
    System.arraycopy(args, 0, result, 1, args.length);
    return result;
  }
}
