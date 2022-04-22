package com.datadog.appsec.event.data;

import datadog.trace.api.Platform;
import java.lang.reflect.*;
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
   * Converts arbitrary objects to strings, maps and lists, by using reflection. This serves two
   * main purposes: - the objects can be inspected by the appsec subsystem and passed to the WAF. -
   * By creating new containers and not transforming only immutable objects like strings, the new
   * object can be safely manipulated by the appsec subsystem without worrying about modifications
   * in other threads.
   *
   * <p>Certain instance fields are excluded. Right now, this includes metaClass fields in Groovy
   * objects and this$0 fields in inner classes.
   *
   * @param obj an arbitrary object
   * @return the converted object
   */
  public static Object convert(Object obj) {
    return guardedConversion(obj, 0, new int[] {MAX_ELEMENTS});
  }

  private static Object guardedConversion(Object obj, int depth, int[] elemsLeft) {
    try {
      return doConversion(obj, depth, elemsLeft);
    } catch (Throwable t) {
      return "<error: " + t.getMessage() + ">";
    }
  }

  private static Object doConversion(Object obj, int depth, int[] elemsLeft) {
    elemsLeft[0]--;
    if (elemsLeft[0] <= 0 || obj == null || depth > MAX_DEPTH) {
      return null;
    }

    // char sequences / numbers
    if (obj instanceof CharSequence || obj instanceof Number) {
      return obj.toString();
    }

    // maps
    if (obj instanceof Map) {
      Map<Object, Object> newMap = new HashMap<>((int) Math.ceil(((Map) obj).size() / .75));
      for (Map.Entry<?, ?> e : ((Map<?, ?>) obj).entrySet()) {
        Object key = e.getKey();
        Object newKey = guardedConversion(e.getKey(), depth + 1, elemsLeft);
        if (newKey == null && key != null) {
          // probably we're out of elements anyway
          continue;
        }
        newMap.put(newKey, guardedConversion(e.getValue(), depth + 1, elemsLeft));
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
        if (elemsLeft[0] <= 0) {
          break;
        }
        newList.add(guardedConversion(o, depth + 1, elemsLeft));
      }
      return newList;
    }

    // arrays
    Class<?> clazz = obj.getClass();
    if (clazz.isArray()) {
      int length = Array.getLength(obj);
      List<Object> newList = new ArrayList<>(length);
      for (int i = 0; i < length && elemsLeft[0] > 0; i++) {
        newList.add(guardedConversion(Array.get(obj, i), depth + 1, elemsLeft));
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
        if (elemsLeft[0] <= 0) {
          break outer;
        }
        if (Modifier.isStatic(f.getModifiers())) {
          continue;
        }
        if (f.getType().getName().equals("groovy.lang.MetaClass")) {
          continue;
        }
        String name = f.getName();
        if (name.equals("this$0")) {
          continue;
        }

        if (setAccessible(f)) {
          try {
            newMap.put(f.getName(), guardedConversion(f.get(obj), depth + 1, elemsLeft));
          } catch (IllegalAccessException e) {
            log.error("Unable to get field value", e);
          }
        } else {
          // One of fields is inaccessible, might be it's Strongly Encapsulated Internal class
          // consider it as integral object without introspection
          return obj.toString();
        }
      }
    }

    return newMap;
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
