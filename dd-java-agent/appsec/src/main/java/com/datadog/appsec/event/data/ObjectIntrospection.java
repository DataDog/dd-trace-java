package com.datadog.appsec.event.data;

import datadog.trace.api.Platform;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ObjectIntrospection {
  private static final int MAX_DEPTH = 20;
  private static final int MAX_ELEMENTS = 256;
  private static final Logger log = LoggerFactory.getLogger(ObjectIntrospection.class);

  private static final Method trySetAccessible;

  static {
    // Method AccessibleObject.trySetAccessible introduced in Java 9
    Method method = null;
    if (Platform.isJavaVersionAtLeast(9)) {
      try {
        method = Field.class.getMethod("trySetAccessible");
      } catch (NoSuchMethodException e) {
        log.error("Can't get method 'Field.trySetAccessible'", e);
      }
    }
    trySetAccessible = method;
  }

  private ObjectIntrospection() {}

  /**
   * Converts arbitrary objects compatible with ddwaf_object. Possible types in the result are:
   *
   * <ul>
   *   <li>Null
   *   <li>Strings
   *   <li>Boolean
   *   <li>Byte, Short, Integer, Long (will be serialized as int64)
   *   <li>Float, Double (will be serialized as float64)
   *   <li>Maps with string keys
   *   <li>Lists
   * </ul>
   *
   * This serves two purposes:
   *
   * <ul>
   *   <li>The objects can be inspected by the appsec subsystem and passed to the WAF.
   *   <li>>By creating new containers and not transforming only immutable objects like strings, the
   *       new object can be safely manipulated by the appsec subsystem without worrying about
   *       modifications in other threads.
   * </ul>
   *
   * <p>Certain instance fields are excluded. Right now, this includes metaClass fields in Groovy
   * objects and this$0 fields in inner classes.
   *
   * <p>Only string values are preserved. Numbers or booleans are removed, since we do not expect
   * rules to detect malicious payloads in these types. An exception to this are map keys, which are
   * always converted to strings.
   *
   * @param obj an arbitrary object
   * @return the converted object
   */
  public static Object convert(Object obj) {
    return guardedConversion(obj, 0, new State());
  }

  private static class State {
    int elemsLeft = MAX_ELEMENTS;
    int invalidKeyId;
  }

  private static Object guardedConversion(Object obj, int depth, State state) {
    try {
      return doConversion(obj, depth, state);
    } catch (Throwable t) {
      // TODO: Use invalid object
      return "error:" + t.getMessage();
    }
  }

  private static String keyConversion(Object key, State state) {
    state.elemsLeft--;
    if (state.elemsLeft <= 0) {
      return null;
    }
    if (key == null) {
      return "null";
    }
    if (key instanceof String) {
      return (String) key;
    }
    if (key instanceof Number
        || key instanceof Boolean
        || key instanceof Character
        || key instanceof CharSequence) {
      return key.toString();
    }
    return "invalid_key:" + (++state.invalidKeyId);
  }

  private static Object doConversion(Object obj, int depth, State state) {
    state.elemsLeft--;
    if (state.elemsLeft <= 0 || obj == null || depth > MAX_DEPTH) {
      return null;
    }

    // strings, booleans and numbers are preserved
    if (obj instanceof String || obj instanceof Boolean || obj instanceof Number) {
      return obj;
    }

    // char sequences are transformed just in case they are not immutable,
    // single char sequences are transformed to strings for ddwaf compatibility.
    if (obj instanceof CharSequence || obj instanceof Character) {
      return obj.toString();
    }

    // maps
    if (obj instanceof Map) {
      Map<Object, Object> newMap = new HashMap<>((int) Math.ceil(((Map) obj).size() / .75));
      for (Map.Entry<?, ?> e : ((Map<?, ?>) obj).entrySet()) {
        Object key = e.getKey();
        Object newKey = keyConversion(e.getKey(), state);
        if (newKey == null && key != null) {
          // probably we're out of elements anyway
          continue;
        }
        newMap.put(newKey, guardedConversion(e.getValue(), depth + 1, state));
      }
      return newMap;
    }

    // iterables
    if (obj instanceof Iterable) {
      List<Object> newList;
      if (obj instanceof List) {
        newList = new ArrayList<>(((List<?>) obj).size());
      } else {
        newList = new ArrayList<>();
      }
      for (Object o : ((Iterable<?>) obj)) {
        if (state.elemsLeft <= 0) {
          break;
        }
        newList.add(guardedConversion(o, depth + 1, state));
      }
      return newList;
    }

    // arrays
    Class<?> clazz = obj.getClass();
    if (clazz.isArray()) {
      int length = Array.getLength(obj);
      List<Object> newList = new ArrayList<>(length);
      for (int i = 0; i < length && state.elemsLeft > 0; i++) {
        newList.add(guardedConversion(Array.get(obj, i), depth + 1, state));
      }
      return newList;
    }

    // else general objects
    Map<String, Object> newMap = new HashMap<>();
    List<Field[]> allFields = new ArrayList<>();
    for (Class<?> classToLook = clazz;
        classToLook != null && classToLook != Object.class;
        classToLook = classToLook.getSuperclass()) {
      allFields.add(classToLook.getDeclaredFields());
    }

    outer:
    for (Field[] fields : allFields) {
      for (Field f : fields) {
        if (state.elemsLeft <= 0) {
          break outer;
        }
        if (Modifier.isStatic(f.getModifiers())) {
          continue;
        }
        if (f.getType().getName().equals("groovy.lang.MetaClass")) {
          continue;
        }
        String name = f.getName();
        if (ignoredFieldName(name)) {
          continue;
        }

        if (setAccessible(f)) {
          try {
            newMap.put(f.getName(), guardedConversion(f.get(obj), depth + 1, state));
          } catch (IllegalAccessException e) {
            log.error("Unable to get field value", e);
            // TODO: Use invalid object
          }
        } else {
          // One of fields is inaccessible, might be it's Strongly Encapsulated Internal class
          // consider it as integral object without introspection
          // TODO: Use invalid object
          return obj.toString();
        }
      }
    }

    return newMap;
  }

  private static boolean ignoredFieldName(final String name) {
    switch (name) {
      case "this$0":
      case "memoizedHashCode":
      case "memoizedSize":
        return true;
      default:
        return false;
    }
  }

  /**
   * Try to make field accessible
   *
   * @param field
   * @return
   */
  private static boolean setAccessible(Field field) {
    try {
      if (trySetAccessible != null) {
        return (boolean) trySetAccessible.invoke(field);
      }
      field.setAccessible(true);
      return true;
    } catch (RuntimeException | IllegalAccessException | InvocationTargetException e) {
      log.error("Unable to make field accessible", e);
      return false;
    }
  }
}
