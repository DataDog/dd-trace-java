package com.datadog.openfeature.internal.ufc;

import java.util.AbstractList;
import java.util.List;
import java.util.RandomAccess;

/**
 * The immutable list every parsed configuration list is stored as: one shared empty list, a single
 * object for one element, and the object plus one exact-size array for more. Elements may be {@code
 * null}.
 *
 * <p>A parsed configuration stays in the old generation until the next one replaces it, and every
 * marking cycle walks it. Most of its lists hold zero or one element, so they shouldn't cost an
 * {@code ArrayList} and its array each.
 *
 * <p>Every parsed list is this one class on purpose. The evaluator iterates model lists with
 * for-each loops: with a single receiver class the JIT inlines {@code iterator()} and escape
 * analysis removes the iterator. Mixing list classes ({@code emptyList}, {@code singletonList},
 * {@code List.of}'s two classes) brings the iterator allocations back, once per loop per
 * evaluation.
 */
final class CompactList<E> extends AbstractList<E> implements RandomAccess {
  private static final Object[] NO_MORE = new Object[0];
  private static final CompactList<Object> EMPTY = new CompactList<>(0, null, NO_MORE);

  private final int size;
  private final E first;
  // The elements after the first.
  private final Object[] more;

  private CompactList(final int size, final E first, final Object[] more) {
    this.size = size;
    this.first = first;
    this.more = more;
  }

  /** Copies {@code list} into the compact form. */
  @SuppressWarnings("unchecked")
  static <E> List<E> copyOf(final List<? extends E> list) {
    switch (list.size()) {
      case 0:
        return (List<E>) EMPTY;
      case 1:
        return new CompactList<>(1, list.get(0), NO_MORE);
      default:
        return new CompactList<>(list.size(), list.get(0), list.subList(1, list.size()).toArray());
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public E get(final int index) {
    if (index < 0 || index >= size) {
      throw new IndexOutOfBoundsException("Index " + index + " out of bounds for length " + size);
    }
    return index == 0 ? first : (E) more[index - 1];
  }

  @Override
  public int size() {
    return size;
  }
}
