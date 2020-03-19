package datadog.trace.agent.tooling;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.Set;

/**
 * Iterates over a class, its superclass, and its interfaces in the following breath-first-like
 * manner:
 *
 * <p>1. BaseClass
 *
 * <p>2. BaseClass's Interfaces
 *
 * <p>3. BaseClass's superclass
 *
 * <p>4. BaseClass's Interfaces' Interfaces
 *
 * <p>5. Superclass's Interfaces
 *
 * <p>6. Superclass's superclass
 *
 * <p>...
 */
public class ClassHierarchyIterable implements Iterable<Class<?>> {
  private final Class<?> baseClass;

  public ClassHierarchyIterable(final Class<?> baseClass) {
    this.baseClass = baseClass;
  }

  @Override
  public Iterator<Class<?>> iterator() {
    return new ClassIterator();
  }

  public class ClassIterator implements Iterator<Class<?>> {
    private Class<?> next;
    private final Set<Class<?>> queuedInterfaces = new HashSet<>();
    private final Queue<Class<?>> classesToExpand = new ArrayDeque<>();

    public ClassIterator() {
      classesToExpand.add(baseClass);
    }

    @Override
    public boolean hasNext() {
      calculateNextIfNecessary();

      return next != null;
    }

    @Override
    public Class<?> next() {
      calculateNextIfNecessary();

      if (next == null) {
        throw new NoSuchElementException();
      }

      final Class<?> next = this.next;
      this.next = null;
      return next;
    }

    @Override
    public void remove() {
      throw new UnsupportedOperationException("remove");
    }

    private void calculateNextIfNecessary() {
      if (next == null && !classesToExpand.isEmpty()) {
        next = classesToExpand.remove();
        queueNewInterfaces(next.getInterfaces());

        final Class<?> superClass = next.getSuperclass();
        if (superClass != null) {
          classesToExpand.add(next.getSuperclass());
        }
      }
    }

    private void queueNewInterfaces(final Class[] interfaces) {
      for (final Class clazz : interfaces) {
        if (queuedInterfaces.add(clazz)) {
          classesToExpand.add(clazz);
        }
      }
    }
  }
}
