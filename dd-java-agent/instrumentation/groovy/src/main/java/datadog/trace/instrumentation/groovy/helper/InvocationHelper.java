package datadog.trace.instrumentation.groovy.helper;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;

import datadog.trace.api.Config;
import datadog.trace.api.iast.csi.DynamicHelper;
import datadog.trace.api.iast.csi.HasDynamicSupport;
import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("deprecation")
public class InvocationHelper {

  private static final Logger LOG = LoggerFactory.getLogger(InvocationHelper.class);

  public static final String CAST_TYPE = "cast";
  public static final String INVOKE_TYPE = "invoke";
  public static final String INIT_TYPE = "init";
  public static final String INIT = "<init>";

  /** Map of method names pointing to the available invokers */
  private static final Map<String, List<CallSiteInvoker>> INVOKERS;

  private static final MethodHandle INVOKE;

  /** Cache to speedup invoker resolution */
  private static final Map<String, Map<MethodType, List<CallSiteInvoker>>> CACHE =
      new ConcurrentHashMap<>();

  static {
    Map<String, List<CallSiteInvoker>> invokers = emptyMap();
    MethodHandle invoke = null;
    try {
      if (Config.get().isIastGroovyIndyEnabled()) {
        invokers = loadCallSiteInvokers();
        invoke =
            MethodHandles.publicLookup()
                .findStatic(
                    InvocationHelper.class,
                    "invoke",
                    MethodType.methodType(
                        Object.class, List.class, CallSite.class, Object[].class));
      }
    } catch (final Throwable e) {
      LOG.error(
          "Failed to initialize invocation helper, no dynamic instrumentation will be available",
          e);
    }
    INVOKERS = invokers;
    INVOKE = invoke;
  }

  /** Check if there's a configured dynamic handler for the specified method name */
  public static boolean supports(final String name) {
    return INVOKERS.containsKey(name);
  }

  /**
   * Tries to find a {@link CallSiteInvoker} capable of adapting the specified {@link
   * java.lang.invoke.CallSite} and args, otherwise invoke the original method without changes
   */
  public static Object invoke(
      final List<CallSiteInvoker> candidates, final CallSite callSite, final Object... arguments)
      throws Throwable {
    for (final CallSiteInvoker invoker : candidates) {
      if (invoker.matchesArguments(arguments)) {
        return invoker.invoke(callSite, arguments);
      }
    }
    return callSite.getTarget().invokeWithArguments(arguments);
  }

  /** Adapts a java dynamic call site to include the call to our own IAST call sites */
  public static java.lang.invoke.CallSite adaptCallSite(
      final String callType,
      final String name,
      final MethodType type,
      final java.lang.invoke.CallSite callSite) {
    if (INVOKE == null) {
      return callSite;
    }
    final List<CallSiteInvoker> invokers = lookupInvokers(callType, type, name);
    if (invokers.isEmpty()) {
      return callSite;
    }
    MethodHandle handle = INVOKE;
    handle = MethodHandles.insertArguments(handle, 0, invokers, callSite);
    handle = handle.asVarargsCollector(Object[].class);
    return new ConstantCallSite(handle.asType(type));
  }

  /** Adapts a java dynamic call site to include the call to our own IAST call sites */
  public static MethodHandle adaptMethodHandle(
      final String callType,
      final String name,
      final MethodHandle target,
      final Object self,
      final Object... args) {
    if (INVOKE == null) {
      return null;
    }
    final MethodType type;
    if (CAST_TYPE.equals(callType)) {
      type = MethodType.methodType((Class<?>) self, getClass(args[0]));
    } else {
      type = insertSelf(self, args);
    }
    final List<CallSiteInvoker> invokers = lookupInvokers(callType, type, name);
    if (invokers.isEmpty()) {
      return null;
    }
    MethodHandle handle = INVOKE;
    handle = MethodHandles.insertArguments(handle, 0, invokers, new ConstantCallSite(target));
    return handle.asVarargsCollector(Object[].class);
  }

  /**
   * Since invocation is dynamic we cannot be sure which method should be executed until it can be
   * resolved at runtime with its actual parameters
   */
  private static List<CallSiteInvoker> lookupInvokers(
      final String callType, final MethodType type, final String name) {
    if (CAST_TYPE.equals(callType)) {
      return findHandlersForCast(type);
    } else if (INIT_TYPE.equals(callType)) {
      return findHandlersForInit(type, name);
    } else if (INVOKE_TYPE.equals(callType)) {
      return findHandlersForInvoke(type, name);
    }
    return emptyList();
  }

  /**
   * Groovy performs a dynamic cast to convert a GString to a String, so treat it like a regular
   * toString() method
   */
  private static List<CallSiteInvoker> findHandlersForCast(final MethodType method) {
    if (method.returnType() != String.class
        && method.parameterCount() < 1
        && !CharSequence.class.isAssignableFrom(method.parameterType(0))) {
      return emptyList();
    }
    return findHandlersForInvoke(method, "toString");
  }

  /** Groovy performs a dynamic invocation with the Class to instantiate and the parameters */
  private static List<CallSiteInvoker> findHandlersForInit(
      final MethodType method, final String name) {
    if (!INIT.equals(name)) {
      return emptyList();
    }
    return findHandlersForInvoke(method, name);
  }

  /**
   * There are two types of invokes, static methods where Groovy appends the Class to the parameters
   * and instance methods where Groovy appends the current instance
   */
  private static List<CallSiteInvoker> findHandlersForInvoke(
      final MethodType method, final String name) {
    final Map<MethodType, List<CallSiteInvoker>> lookup =
        CACHE.computeIfAbsent(name, k -> new ConcurrentHashMap<>());
    return lookup.computeIfAbsent(
        method,
        m -> {
          final List<CallSiteInvoker> invokers = INVOKERS.get(name);
          if (invokers == null) {
            return emptyList();
          }
          final List<CallSiteInvoker> matching = new ArrayList<>(invokers.size());
          for (final CallSiteInvoker invoker : invokers) {
            if (invoker.matchesSignature(method)) {
              matching.add(invoker);
            }
          }
          return matching;
        });
  }

  private static Map<String, List<CallSiteInvoker>> loadCallSiteInvokers() {
    final Map<String, List<CallSiteInvoker>> invokers = new HashMap<>();
    final ClassLoader loader = InvocationHelper.class.getClassLoader();
    for (final Class<? extends HasDynamicSupport> target : HasDynamicSupport.Loader.load(loader)) {
      try {
        for (final Method method : target.getDeclaredMethods()) {
          final DynamicHelper helper = method.getAnnotation(DynamicHelper.class);
          if (helper != null) {
            final String methodName = helper.method();
            final CallSiteInvoker invoker = new CallSiteInvoker(helper, method);
            invokers.computeIfAbsent(methodName, n -> new ArrayList<>()).add(invoker);
          }
        }
      } catch (final Throwable e) {
        LOG.debug("Failed to handle dynamic helper {}", target, e);
      }
    }
    return invokers;
  }

  private static MethodType insertSelf(final Object self, final Object... args) {
    final Class<?>[] argumentClasses = new Class<?>[args.length + 1];
    argumentClasses[0] = self == Class.class ? Class.class : getClass(self);
    for (int i = 0; i < args.length; i++) {
      argumentClasses[i + 1] = getClass(args[i]);
    }
    return MethodType.methodType(Object.class, argumentClasses);
  }

  private static Class<?> getClass(final Object object) {
    return object == null ? Object.class : object.getClass();
  }
}
