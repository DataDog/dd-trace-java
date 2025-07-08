package com.datadog.iast.util;

import static com.datadog.iast.util.ObjectVisitor.State.CONTINUE;
import static com.datadog.iast.util.ObjectVisitor.State.EXIT;

import datadog.environment.JavaVirtualMachine;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ObjectVisitor {

  private static final Logger LOGGER = LoggerFactory.getLogger(ObjectVisitor.class);
  private static final int MAX_VISITED_OBJECTS = 1000;
  private static final int MAX_DEPTH = 10;
  @Nullable private static final Method TRY_SET_ACCESSIBLE;

  static {
    TRY_SET_ACCESSIBLE = fetchTrySetAccessibleMethod();
  }

  public static void visit(
      @Nonnull final Object object,
      @Nonnull final Visitor visitor,
      @Nonnull final Predicate<Class<?>> classFilter) {
    visit(object, visitor, classFilter, MAX_DEPTH, MAX_VISITED_OBJECTS);
  }

  public static void visit(
      @Nonnull final Object object,
      @Nonnull final Visitor visitor,
      @Nonnull final Predicate<Class<?>> classFilter,
      final int maxDepth,
      final int maxObjects) {
    new ObjectVisitor(classFilter, maxDepth, maxObjects, visitor).visit(0, "root", object);
  }

  private int remaining;
  private final int maxDepth;
  private final Set<Object> visited;
  private final Visitor visitor;
  private final Predicate<Class<?>> classFilter;

  private ObjectVisitor(
      final Predicate<Class<?>> classFilter,
      final int maxDepth,
      final int maxObjects,
      final Visitor visitor) {
    this.maxDepth = maxDepth;
    this.remaining = maxObjects;
    this.visited = Collections.newSetFromMap(new IdentityHashMap<>());
    this.visitor = visitor;
    this.classFilter = classFilter;
  }

  private State visit(final int depth, final String path, final Object value) {
    if (remaining <= 0) {
      return EXIT;
    }
    remaining--;
    if (depth > maxDepth) {
      return CONTINUE;
    }
    if (!visited.add(value)) {
      return CONTINUE;
    }
    State state = CONTINUE;
    try {
      if (value instanceof Object[]) {
        state = visitArray(depth, path, (Object[]) value);
      } else if (value instanceof Map) {
        state = visitMap(depth, path, (Map<?, ?>) value);
      } else if (value instanceof Iterable) {
        state = visitIterable(depth, path, (Iterable<?>) value);
      } else {
        state = visitObject(depth, path, value);
      }
    } catch (final Throwable e) {
      LOGGER.debug("Failed to visit object of type {}", value.getClass(), e);
    }
    return state;
  }

  private State visitArray(final int depth, final String path, final Object[] array) {
    final int arrayDepth = depth + 1;
    for (int i = 0; i < array.length; i++) {
      final Object item = array[i];
      if (item != null) {
        final String itemPath = path + "[" + i + "]";
        final State state = visit(arrayDepth, itemPath, item);
        if (state != CONTINUE) {
          return state;
        }
      }
    }
    return CONTINUE;
  }

  private State visitMap(final int depth, final String path, final Map<?, ?> map) {
    if (!classFilter.test(map.getClass())) {
      return CONTINUE;
    }
    final int mapDepth = depth + 1;
    for (final Map.Entry<?, ?> entry : map.entrySet()) {
      final Object key = entry.getKey();
      if (key != null) {
        final String keyPath = path + "[]";
        final ObjectVisitor.State state = visit(mapDepth, keyPath, key);
        if (state != CONTINUE) {
          return state;
        }
      }
      final Object item = entry.getValue();
      if (item != null) {
        final String itemPath = path + "[" + key + "]";
        final State state = visit(mapDepth, itemPath, item);
        if (state != CONTINUE) {
          return state;
        }
      }
    }
    return CONTINUE;
  }

  private State visitIterable(final int depth, final String path, final Iterable<?> iterable) {
    if (!classFilter.test(iterable.getClass())) {
      return CONTINUE;
    }
    final int iterableDepth = depth + 1;
    int index = 0;
    for (final Object item : iterable) {
      if (item != null) {
        final String itemPath = path + "[" + (index++) + "]";
        final State state = visit(iterableDepth, itemPath, item);
        if (state != CONTINUE) {
          return state;
        }
      }
    }
    return CONTINUE;
  }

  private State visitObject(final int depth, final String path, final Object value) {
    final int childDepth = depth + 1;
    State state = visitor.visit(path, value);
    if (state != State.CONTINUE || !classFilter.test(value.getClass())) {
      return state;
    }
    Class<?> klass = value.getClass();
    while (klass != Object.class) {
      for (final Field field : klass.getDeclaredFields()) {
        try {
          if (inspectField(field) && trySetAccessible(field)) {
            final Object fieldValue = field.get(value);
            if (fieldValue != null) {
              final String fieldPath = path + "." + field.getName();
              state = visit(childDepth, fieldPath, fieldValue);
              if (state != CONTINUE) {
                return state;
              }
            }
          }
        } catch (final Throwable e) {
          LOGGER.debug("Unable to get field {}", field, e);
        }
      }
      klass = klass.getSuperclass();
    }
    return ObjectVisitor.State.CONTINUE;
  }

  private static boolean inspectField(final Field field) {
    final int modifiers = field.getModifiers();
    if (Modifier.isStatic(modifiers)) {
      return false;
    }
    final String fieldName = field.getName();
    if ("this$0".equals(fieldName)) {
      return false; // skip back references from inner class
    }
    final Class<?> fieldType = field.getType();
    if ("groovy.lang.MetaClass".equals(fieldType.getName())) {
      return false; // skip the whole groovy MOP
    }
    return true;
  }

  @Nullable
  private static Method fetchTrySetAccessibleMethod() {
    Method method = null;
    if (JavaVirtualMachine.isJavaVersionAtLeast(9)) {
      try {
        method = Field.class.getMethod("trySetAccessible");
      } catch (NoSuchMethodException e) {
        LOGGER.warn("Can't get method 'Field.trySetAccessible'", e);
      }
    }
    return method;
  }

  private static boolean trySetAccessible(final Field field) {
    try {
      if (TRY_SET_ACCESSIBLE != null) {
        return (boolean) TRY_SET_ACCESSIBLE.invoke(field);
      }
      field.setAccessible(true);
      return true;
    } catch (RuntimeException | IllegalAccessException | InvocationTargetException e) {
      LOGGER.debug("Unable to make field accessible", e);
      return false;
    }
  }

  public interface Visitor {
    @Nonnull
    State visit(@Nonnull String path, @Nonnull Object value);
  }

  public enum State {
    CONTINUE,
    EXIT
  }
}
