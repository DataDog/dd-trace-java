package datadog.trace.instrumentation.groovy;

import static datadog.trace.instrumentation.groovy.helper.InvocationHelper.INIT;
import static datadog.trace.instrumentation.groovy.helper.InvocationHelper.INIT_TYPE;
import static datadog.trace.instrumentation.groovy.helper.InvocationHelper.INVOKE_TYPE;
import static datadog.trace.instrumentation.groovy.helper.InvocationHelper.adaptMethodHandle;

import datadog.trace.agent.tooling.csi.CallSite;
import datadog.trace.api.iast.IastCallSites;
import datadog.trace.api.iast.InstrumentationBridge;
import datadog.trace.api.iast.Propagation;
import datadog.trace.api.iast.csi.SkipDynamicHelpers;
import datadog.trace.api.iast.propagation.StringModule;
import datadog.trace.instrumentation.groovy.helper.CallSiteInvoker;
import datadog.trace.instrumentation.groovy.helper.InvocationHelper;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.codehaus.groovy.runtime.InvokerHelper;
import org.codehaus.groovy.runtime.ScriptBytecodeAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Propagation
@CallSite(
    spi = IastCallSites.class,
    helpers = {InvocationHelper.class, CallSiteInvoker.class},
    enabled = {"datadog.trace.api.iast.IastEnabledChecks", "isGroovyIndyEnabled"})
@SkipDynamicHelpers
public class ScriptBytecodeAdapterCallSite {

  private static final Logger LOG = LoggerFactory.getLogger(ScriptBytecodeAdapterCallSite.class);

  private static final Object NOT_FOUND = new Object();

  private static final Map<String, MethodHandle> CACHE = new ConcurrentHashMap<>();

  @SuppressWarnings("unused")
  @CallSite.After(
      "java.lang.Object org.codehaus.groovy.runtime.ScriptBytecodeAdapter.castToType(java.lang.Object, java.lang.Class)")
  public static Object castToType(
      @CallSite.Argument final Object object,
      @CallSite.Argument final Class<?> targetClass,
      @CallSite.Return final Object result) {
    final StringModule module = InstrumentationBridge.STRING;
    if (module != null && object instanceof CharSequence && result instanceof CharSequence) {
      try {
        module.onStringToString((CharSequence) object, (CharSequence) result);
      } catch (final Throwable e) {
        module.onUnexpectedException("afterCastToType threw", e);
      }
    }
    return result;
  }

  @CallSite.Around(
      "java.lang.Object org.codehaus.groovy.runtime.ScriptBytecodeAdapter.invokeMethodN(java.lang.Class, java.lang.Object, java.lang.String, java.lang.Object[])")
  public static Object invokeMethodN(
      @CallSite.Argument final Class<?> sender,
      @CallSite.Argument final Object receiver,
      @CallSite.Argument final String method,
      @CallSite.Argument final Object[] args)
      throws Throwable {
    if (InvocationHelper.supports(method)) {
      final Object result =
          internalInvoke("invokeMethodN", sender, INVOKE_TYPE, method, receiver, args);
      if (result != NOT_FOUND) {
        return result;
      }
    }
    return ScriptBytecodeAdapter.invokeMethodN(sender, receiver, method, args);
  }

  @CallSite.Around(
      "java.lang.Object org.codehaus.groovy.runtime.ScriptBytecodeAdapter.invokeMethodNSafe(java.lang.Class, java.lang.Object, java.lang.String, java.lang.Object[])")
  public static Object invokeMethodNSafe(
      @CallSite.Argument final Class<?> sender,
      @CallSite.Argument final Object receiver,
      @CallSite.Argument final String method,
      @CallSite.Argument final Object[] args)
      throws Throwable {
    if (InvocationHelper.supports(method)) {
      final Object result =
          internalInvoke("invokeMethodNSafe", sender, INVOKE_TYPE, method, receiver, args);
      if (result != NOT_FOUND) {
        return result;
      }
    }
    return ScriptBytecodeAdapter.invokeMethodNSafe(sender, receiver, method, args);
  }

  @CallSite.Around(
      "java.lang.Object org.codehaus.groovy.runtime.ScriptBytecodeAdapter.invokeMethodNSpreadSafe(java.lang.Class, java.lang.Object, java.lang.String, java.lang.Object[])")
  public static Object invokeMethodNSpreadSafe(
      @CallSite.Argument final Class<?> sender,
      @CallSite.Argument final Object receiver,
      @CallSite.Argument final String method,
      @CallSite.Argument final Object[] args)
      throws Throwable {
    if (InvocationHelper.supports(method)) {
      final Object result =
          internalInvoke("invokeMethodNSpreadSafe", sender, INVOKE_TYPE, method, receiver, args);
      if (result != NOT_FOUND) {
        return result;
      }
    }
    return ScriptBytecodeAdapter.invokeMethodNSpreadSafe(sender, receiver, method, args);
  }

  @CallSite.Around(
      "java.lang.Object org.codehaus.groovy.runtime.ScriptBytecodeAdapter.invokeMethod0(java.lang.Class, java.lang.Object, java.lang.String)")
  public static Object invokeMethod0(
      @CallSite.Argument final Class<?> sender,
      @CallSite.Argument final Object receiver,
      @CallSite.Argument final String method)
      throws Throwable {
    if (InvocationHelper.supports(method)) {
      final Object result = internalInvoke("invokeMethod0", sender, INVOKE_TYPE, method, receiver);
      if (result != NOT_FOUND) {
        return result;
      }
    }
    return ScriptBytecodeAdapter.invokeMethod0(sender, receiver, method);
  }

  @CallSite.Around(
      "java.lang.Object org.codehaus.groovy.runtime.ScriptBytecodeAdapter.invokeMethod0Safe(java.lang.Class, java.lang.Object, java.lang.String)")
  public static Object invokeMethod0Safe(
      @CallSite.Argument final Class<?> sender,
      @CallSite.Argument final Object receiver,
      @CallSite.Argument final String method)
      throws Throwable {
    if (InvocationHelper.supports(method)) {
      final Object result =
          internalInvoke("invokeMethod0Safe", sender, INVOKE_TYPE, method, receiver);
      if (result != NOT_FOUND) {
        return result;
      }
    }
    return ScriptBytecodeAdapter.invokeMethod0Safe(sender, receiver, method);
  }

  @CallSite.Around(
      "java.lang.Object org.codehaus.groovy.runtime.ScriptBytecodeAdapter.invokeMethod0SpreadSafe(java.lang.Class, java.lang.Object, java.lang.String)")
  public static Object invokeMethod0SpreadSafe(
      @CallSite.Argument final Class<?> sender,
      @CallSite.Argument final Object receiver,
      @CallSite.Argument final String method)
      throws Throwable {
    if (InvocationHelper.supports(method)) {
      final Object result =
          internalInvoke("invokeMethod0SpreadSafe", sender, INVOKE_TYPE, method, receiver);
      if (result != NOT_FOUND) {
        return result;
      }
    }
    return ScriptBytecodeAdapter.invokeMethod0SpreadSafe(sender, receiver, method);
  }

  @CallSite.Around(
      "java.lang.Object org.codehaus.groovy.runtime.ScriptBytecodeAdapter.invokeStaticMethodN(java.lang.Class, java.lang.Class, java.lang.String, java.lang.Object[])")
  public static Object invokeStaticMethodN(
      @CallSite.Argument final Class<?> sender,
      @CallSite.Argument final Class<?> receiver,
      @CallSite.Argument final String method,
      @CallSite.Argument final Object[] args)
      throws Throwable {
    if (InvocationHelper.supports(method)) {
      final Object result =
          internalInvoke("invokeStaticMethodN", sender, INVOKE_TYPE, method, receiver, args);
      if (result != NOT_FOUND) {
        return result;
      }
    }
    return ScriptBytecodeAdapter.invokeStaticMethodN(sender, receiver, method, args);
  }

  @CallSite.Around(
      "java.lang.Object org.codehaus.groovy.runtime.ScriptBytecodeAdapter.invokeStaticMethod0(java.lang.Class, java.lang.Class, java.lang.String)")
  public static Object invokeStaticMethod0(
      @CallSite.Argument final Class<?> sender,
      @CallSite.Argument final Class<?> receiver,
      @CallSite.Argument final String method)
      throws Throwable {
    if (InvocationHelper.supports(method)) {
      final Object result =
          internalInvoke("invokeStaticMethod0", sender, INVOKE_TYPE, method, receiver);
      if (result != NOT_FOUND) {
        return result;
      }
    }
    return ScriptBytecodeAdapter.invokeStaticMethod0(sender, receiver, method);
  }

  @CallSite.Around(
      "java.lang.Object org.codehaus.groovy.runtime.ScriptBytecodeAdapter.invokeNewN(java.lang.Class, java.lang.Class, java.lang.Object)")
  public static Object invokeNewN(
      @CallSite.Argument final Class<?> sender,
      @CallSite.Argument final Class<?> receiver,
      @CallSite.Argument final Object args)
      throws Throwable {
    if (InvocationHelper.supports(INIT)) {
      final Object[] arguments = InvokerHelper.asArray(args);
      final Object result =
          internalInvoke("invokeNewN", sender, INIT_TYPE, INIT, receiver, arguments);
      if (result != NOT_FOUND) {
        return result;
      }
    }
    return ScriptBytecodeAdapter.invokeNewN(sender, receiver, args);
  }

  @CallSite.Around(
      "java.lang.Object org.codehaus.groovy.runtime.ScriptBytecodeAdapter.invokeNew0(java.lang.Class, java.lang.Class)")
  public static Object invokeNew0(
      @CallSite.Argument final Class<?> sender, @CallSite.Argument final Class<?> receiver)
      throws Throwable {
    if (InvocationHelper.supports(INIT)) {
      final Object result = internalInvoke("invokeNew0", sender, INIT_TYPE, INIT, receiver);
      if (result != NOT_FOUND) {
        return result;
      }
    }
    return ScriptBytecodeAdapter.invokeNew0(sender, receiver);
  }

  private static Object internalInvoke(
      final String scriptMethodName,
      final Class<?> sender,
      final String callType,
      final String method,
      final Object receiver,
      final Object... args) {
    try {
      MethodHandle target = resolveMethod(scriptMethodName, sender);
      if (!INIT_TYPE.equals(callType)) {
        target = injectMethodName(target, method);
        if (args.length > 0) {
          target = target.asCollector(Object[].class, args.length);
        }
      }
      final MethodHandle adapter = adaptMethodHandle(callType, method, target, receiver, args);
      if (adapter != null) {
        return adapter.invokeWithArguments(flatten(receiver, args));
      }
    } catch (final Throwable e) {
      LOG.error("Error on {} handler", scriptMethodName, e);
    }
    return NOT_FOUND;
  }

  private static MethodHandle resolveMethod(final String scriptMethod, final Object... toInsert) {
    MethodHandle handle =
        CACHE.computeIfAbsent(
            scriptMethod,
            t -> {
              try {
                for (final Method method : ScriptBytecodeAdapter.class.getDeclaredMethods()) {
                  final int modifiers = method.getModifiers();
                  if (scriptMethod.equals(method.getName())
                      && Modifier.isStatic(modifiers)
                      && Modifier.isPublic(modifiers)) {
                    return MethodHandles.publicLookup().unreflect(method);
                  }
                }
                throw new RuntimeException(
                    "Method not found in ScriptBytecodeAdapter: " + scriptMethod);
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            });
    if (toInsert.length > 0) {
      handle = MethodHandles.insertArguments(handle, 0, toInsert);
    }
    return handle;
  }

  private static MethodHandle injectMethodName(final MethodHandle handle, final String name) {
    final MethodHandle result = switchFirstArguments(handle);
    return MethodHandles.insertArguments(result, 0, name);
  }

  private static MethodHandle switchFirstArguments(final MethodHandle handle) {
    final MethodType type = handle.type();
    final Class<?>[] args = type.parameterArray();
    final Class<?> first = args[0];
    args[0] = args[1];
    args[1] = first;
    final int[] permutations = new int[args.length];
    permutations[0] = 1;
    permutations[1] = 0;
    for (int i = 2; i < permutations.length; i++) {
      permutations[i] = i;
    }
    final MethodType switchedType = MethodType.methodType(type.returnType(), args);
    return MethodHandles.permuteArguments(handle, switchedType, permutations);
  }

  private static Object[] flatten(final Object element, final Object[] args) {
    final Object[] result = new Object[args.length + 1];
    result[0] = element;
    System.arraycopy(args, 0, result, 1, args.length);
    return result;
  }
}
