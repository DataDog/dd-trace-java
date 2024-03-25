package com.datadog.iast.util;

import java.util.Iterator;
import java.util.NoSuchElementException;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

public abstract class Iterators {

  private static final Iterator<?> EMPTY =
      new Iterator<Object>() {
        @Override
        public boolean hasNext() {
          return false;
        }

        @Override
        public Object next() {
          throw new NoSuchElementException();
        }
      };

  private Iterators() {}

  @SuppressWarnings("unchecked")
  public static <E> Iterator<E> empty() {
    return (Iterator<E>) EMPTY;
  }

  @Nonnull
  public static <E> Iterator<E> of(@Nonnull final E head, @Nullable final E[] tail) {
    return new HeadedArrayIterator<>(head, tail);
  }

  @SuppressWarnings("unchecked")
  @Nonnull
  public static <E> Iterator<E> of(@Nullable final E... items) {
    return items == null || items.length == 0 ? empty() : new ArrayIterator<>(items);
  }

  @Nonnull
  public static Iterator<?> join(@Nullable final Iterator<?>... iterators) {
    return iterators == null || iterators.length == 0 ? empty() : new JoinIterator(iterators);
  }

  private static class HeadedArrayIterator<E> extends ArrayIterator<E> {

    private static final Object[] EMPTY_TAIL = new Object[0];

    private boolean first;

    private final E head;

    @SuppressWarnings("unchecked")
    private HeadedArrayIterator(final E head, @Nullable final E[] tail) {
      super(tail == null ? (E[]) EMPTY_TAIL : tail);
      this.head = head;
      this.first = true;
    }

    @Override
    public boolean hasNext() {
      return first || super.hasNext();
    }

    @Override
    public E next() {
      if (first) {
        first = false;
        return head;
      }
      return super.next();
    }
  }

  private static class ArrayIterator<E> implements Iterator<E> {
    private final E[] items;
    private int index = 0;

    private ArrayIterator(@Nonnull final E[] items) {
      this.items = items;
    }

    @Override
    public boolean hasNext() {
      return index < items.length;
    }

    @Override
    public E next() {
      if (index >= items.length) {
        throw new NoSuchElementException();
      }
      return items[index++];
    }
  }

  private static class JoinIterator implements Iterator<Object> {
    private final Iterator<?>[] iterators;
    private int index;
    @Nullable private Iterator<?> current;

    private JoinIterator(@Nonnull final Iterator<?>[] iterators) {
      this.iterators = iterators;
      current = iterators[0];
      index = 0;
    }

    @Override
    public boolean hasNext() {
      return current != null && current.hasNext();
    }

    @Override
    public Object next() {
      if (current == null || !current.hasNext()) {
        throw new NoSuchElementException();
      }
      final Object result = current.next();
      if (!current.hasNext()) {
        index++;
        current = index >= iterators.length ? null : iterators[index];
      }
      return result;
    }
  }
}
