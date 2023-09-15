package com.datadog.iast.util;

import datadog.trace.api.Platform;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import javax.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ObjectVisitor {

  private static final Logger LOGGER = LoggerFactory.getLogger(ObjectVisitor.class);

  private static final int MAX_VISITED_OBJECTS = 1000;
  private static final int MAX_DEPTH = 10;

  private static final Method TRY_SET_ACCESSIBLE;

  static {
    Method method = null;
    if (Platform.isJavaVersionAtLeast(9)) {
      try {
        method = Field.class.getMethod("trySetAccessible");
      } catch (NoSuchMethodException e) {
        LOGGER.error("Can't get method 'Field.trySetAccessible'", e);
      }
    }
    TRY_SET_ACCESSIBLE = method;
  }

  public static void visit(@Nonnull final Object object, @Nonnull final Visitor visitor) {
    visit(object, visitor, MAX_DEPTH, MAX_VISITED_OBJECTS);
  }

  public static void visit(
      @Nonnull final Object object,
      @Nonnull final Visitor visitor,
      final int maxDepth,
      final int maxObjects) {
    visit(
        object,
        visitor,
        maxDepth,
        maxObjects,
        ObjectVisitor::includedClasses,
        ObjectVisitor::includedFields);
  }

  @SuppressWarnings("unchecked")
  public static void visit(
      @Nonnull final Object object,
      @Nonnull final Visitor visitor,
      final int maxDepth,
      final int maxObjects,
      @Nonnull final Predicate<Class<?>> classes,
      @Nonnull final Predicate<Field> fields) {
    final Set<Object> visited = new HashSet<>();
    final BoundedContextQueue queue = new BoundedContextQueue(maxDepth, maxObjects);
    queue.push(0, "root", object);
    // BFS
    while (!queue.isEmpty()) {
      final Context<?> current = queue.pop();
      final Object value = current.value;
      if (visited.contains(value)) {
        continue;
      }
      visited.add(value);
      try {
        if (value instanceof Object[]) {
          visitArray(queue, (Context<Object[]>) current);
        } else if (value instanceof Map) {
          visitMap(queue, (Context<Map<?, ?>>) current);
        } else if (value instanceof Iterable) {
          visitIterable(queue, (Context<Iterable<?>>) current);
        } else {
          final State state =
              visitObject(queue, (Context<Object>) current, visitor, classes, fields);
          if (state == State.EXIT) {
            return;
          }
        }
      } catch (final Throwable e) {
        LOGGER.warn("Failed to visit object of type {}", value.getClass(), e);
      }
    }
  }

  private static void visitArray(
      @Nonnull final BoundedContextQueue queue, @Nonnull final Context<Object[]> current) {
    final int depth = current.depth + 1;
    for (int i = 0; i < current.value.length; i++) {
      final Object item = current.value[i];
      if (item != null) {
        final String itemPath = current.path + "[" + i + "]";
        if (!queue.push(depth, itemPath, item)) {
          break;
        }
      }
    }
  }

  private static void visitMap(
      @Nonnull final BoundedContextQueue queue, @Nonnull final Context<Map<?, ?>> current) {
    final int depth = current.depth + 1;
    for (final Map.Entry<?, ?> entry : current.value.entrySet()) {
      final Object key = entry.getKey();
      if (key != null) {
        final String keyPath = current.path + "[]";
        if (!queue.push(depth, keyPath, entry.getKey())) {
          break;
        }
      }
      final Object item = entry.getValue();
      if (item != null) {
        final String itemPath = current.path + "[" + key + "]";
        if (!queue.push(depth, itemPath, item)) {
          break;
        }
      }
    }
  }

  private static void visitIterable(
      @Nonnull final BoundedContextQueue queue, @Nonnull final Context<Iterable<?>> current) {
    final int depth = current.depth + 1;
    int index = 0;
    for (final Object item : current.value) {
      if (item != null) {
        final String itemPath = current.path + "[" + (index++) + "]";
        if (!queue.push(depth, itemPath, item)) {
          break;
        }
      }
    }
  }

  private static State visitObject(
      @Nonnull final BoundedContextQueue queue,
      @Nonnull final Context<Object> current,
      @Nonnull final Visitor visitor,
      @Nonnull final Predicate<Class<?>> classes,
      @Nonnull final Predicate<Field> fields) {
    final int childDepth = current.depth + 1;
    final String path = current.path;
    final Object value = current.value;
    final State state = visitor.visit(path, value);
    if (state != State.CONTINUE || !classes.test(value.getClass())) {
      return state;
    }
    Class<?> klass = current.value.getClass();
    while (klass != Object.class) {
      for (final Field field : klass.getDeclaredFields()) {
        try {
          if (fields.test(field) && setAccessible(field)) {
            final Object fieldValue = field.get(value);
            if (fieldValue != null) {
              final String fieldPath = path + "." + field.getName();
              if (!queue.push(childDepth, fieldPath, fieldValue)) {
                return State.CONTINUE;
              }
            }
          }
        } catch (final Throwable e) {
          LOGGER.warn("Unable to get field {}", field, e);
        }
      }
      klass = klass.getSuperclass();
    }
    return State.CONTINUE;
  }

  public static boolean includedClasses(final Class<?> cls) {
    if (cls.isPrimitive()) {
      return false; // skip primitives
    }
    if (cls.getPackage().getName().startsWith("java")) {
      return false; // do not visit JDK classes properties
    }
    return true;
  }

  public static boolean includedFields(final Field field) {
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

  private static boolean setAccessible(final Field field) {
    try {
      if (TRY_SET_ACCESSIBLE != null) {
        return (boolean) TRY_SET_ACCESSIBLE.invoke(field);
      }
      field.setAccessible(true);
      return true;
    } catch (RuntimeException | IllegalAccessException | InvocationTargetException e) {
      LOGGER.warn("Unable to make field accessible", e);
      return false;
    }
  }

  public interface Visitor {
    State visit(@Nonnull final String path, @Nonnull final Object value);
  }

  public enum State {
    CONTINUE,
    EXIT
  }

  private static class Context<E> {
    private final int depth;
    @Nonnull private final String path;
    @Nonnull private final E value;

    private Context(final int depth, @Nonnull final String path, @Nonnull final E value) {
      this.depth = depth;
      this.path = path;
      this.value = value;
    }
  }

  /** Queue with a bounded number of pushes */
  private static class BoundedContextQueue {

    private final LinkedList<Context<?>> delegate = new LinkedList<>();
    private final int maxDepth;
    private int remainder;

    public BoundedContextQueue(final int maxDepth, final int maxObjects) {
      this.maxDepth = maxDepth;
      remainder = maxObjects;
    }

    public Context<?> pop() {
      return delegate.remove();
    }

    public <E> boolean push(final int depth, final String path, final E object) {
      if (remainder == 0) {
        return false; // ok no more pushes
      }
      remainder--;

      // ignore if beyond max depth
      if (depth <= maxDepth) {
        delegate.add(new Context<>(depth, path, object));
      }
      return true;
    }

    public boolean isEmpty() {
      return delegate.isEmpty();
    }
  }
}
