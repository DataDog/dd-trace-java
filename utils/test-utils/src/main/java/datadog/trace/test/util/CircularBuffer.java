package datadog.trace.test.util;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Circular buffer to prevent last N objects from being garbage-collected. This is not thread-safe.
 * Iterable by insertion order. The iterator omits nulls.
 */
public class CircularBuffer<T> implements Iterable<T> {
  private int index;
  private final int lengthMask;
  private final T[] buffer;

  public CircularBuffer(final int capacity) {
    assert capacity > 0;
    // Stick to power of 2
    assert (capacity & (capacity - 1)) == 0;
    lengthMask = capacity - 1;
    index = 0;
    buffer = createBuffer(capacity);
  }

  public T add(final T obj) {
    final T current = buffer[index];
    buffer[index] = obj;
    index = (index + 1) & lengthMask;
    return current;
  }

  public Iterator<T> iterator() {
    final List<T> list = new ArrayList<>();
    int iterIndex = (index + 1) & lengthMask;
    for (int i = 0; i < buffer.length; i++) {
      final T el = buffer[iterIndex];
      if (el != null) {
        list.add(el);
      }
      iterIndex = (iterIndex + 1) & lengthMask;
    }
    return list.iterator();
  }

  @SuppressWarnings("unchecked")
  private T[] createBuffer(final int capacity) {
    return (T[]) new Object[capacity];
  }
}
