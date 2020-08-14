package datadog.trace.agent.tooling.log;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * @param <T> something like String to String map. DD tags will be injected in T only if T is
 *     instance of Map, or has T#put(String, Object) method, or putValue(String, Object) method.
 */
@Slf4j
public class ThreadLocalWithDDTagsInitValue<T> extends ThreadLocal<T> {
  private static final String[] PUT_METHODS_POSSIBLE_NAMES_IN_ORDER =
      new String[] {"put", "putValue"};

  private static Method findMethodWithName(final Method[] methods, final String methodName) {
    for (final Method method : methods) {
      if (method.getName().equals(methodName)) {
        return method;
      }
    }
    return null;
  }

  /**
   * try to find method with name in {@param methods} in order of names listed in {@param
   * methodNames}
   */
  private static Method findMethodWithNamesByOrder(
      final Method[] methods, final String[] methodNames) {
    for (final String methodName : methodNames) {
      final Method method = findMethodWithName(methods, methodName);
      if (method != null) {
        return method;
      }
    }
    return null;
  }

  private static <T> T putToMap(final T stringToObjMap, final Map<String, ?> whatToPut)
      throws InvocationTargetException, IllegalAccessException {
    if (stringToObjMap instanceof Map) {
      ((Map) stringToObjMap).putAll(whatToPut);
    } else {
      final Class<?> cl = stringToObjMap.getClass();
      final Method[] methods = cl.getDeclaredMethods();
      final Method method =
          findMethodWithNamesByOrder(methods, PUT_METHODS_POSSIBLE_NAMES_IN_ORDER);
      if (method != null) {
        final Class<?>[] parameterTypes = method.getParameterTypes();
        if (parameterTypes.length != 2 || !String.class.equals(parameterTypes[0])) {
          log.warn(
              "Can't find a way how to add DD tags to '{}'; was trying to use {}, "
                  + "but didn't like it's signature",
              stringToObjMap,
              method);
          return null;
        }
        method.setAccessible(true);
        for (final Map.Entry<String, ?> entry : whatToPut.entrySet()) {
          method.invoke(stringToObjMap, entry.getKey(), entry.getValue());
        }
      } else {
        log.warn("Can't find a method how to add DD tags to '{}'", stringToObjMap);
        return null;
      }
    }
    return stringToObjMap;
  }

  public static <T> ThreadLocal<T> create(T origThreadLocalValue)
      throws InvocationTargetException, IllegalAccessException {
    // eg logback implementation uses synchronization on old instance of the map:
    // https://github.com/qos-ch/logback/blob/a6356170acfa6ce6e2383477bf80e6cae8a82d80/logback-classic/src/main/java/ch/qos/logback/classic/util/LogbackMDCAdapter.java#L77
    // here we try to follow this contract:
    synchronized (origThreadLocalValue) {
      putToMap(origThreadLocalValue, LogContextScopeListener.LOG_CONTEXT_DD_TAGS);
    }
    return new ThreadLocalWithDDTagsInitValue<>(origThreadLocalValue);
  }

  private final T initValue;

  private ThreadLocalWithDDTagsInitValue() {
    log.warn("This constructor should never be called");
    initValue = null;
  }

  private ThreadLocalWithDDTagsInitValue(final T initValue) {
    this.initValue = initValue;
  }

  @Override
  protected T initialValue() {
    return initValue;
  }
}
