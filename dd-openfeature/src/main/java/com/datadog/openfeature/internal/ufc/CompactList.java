package com.datadog.openfeature.internal.ufc;

import java.util.AbstractList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.RandomAccess;
import java.util.function.Consumer;

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
 *
 * <p>The iterator is this class's own, not {@code AbstractList}'s. {@code AbstractList}'s iterator
 * is shared by every {@code AbstractList} subclass, so its {@code size()}, {@code get()} and {@code
 * modCount} calls see every list class in the application. Once they can't be inlined, escape
 * analysis keeps the iterator. Likewise {@code isEmpty()} and {@code forEach} are overridden rather
 * than inherited. {@code iterator()} returns a new iterator on every path, the empty list included:
 * returning a shared one on some path would merge two objects, and escape analysis can't remove a
 * merged allocation.
 */
final class CompactList<E> extends AbstractList<E> implements RandomAccess {
  private static final Object[] NO_MORE = new Object[0];
  // The only list whose `more` is null, so the size needs no field of its own: with AbstractList's
  // modCount, an extra int field would pad every list from 24 to 32 bytes.
  private static final CompactList<Object> EMPTY = new CompactList<>(null, null);

  private final E first;
  // The elements after the first: null for the empty list, NO_MORE for one element.
  private final Object[] more;

  private CompactList(final E first, final Object[] more) {
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
        return new CompactList<>(list.get(0), NO_MORE);
      default:
        return new CompactList<>(list.get(0), list.subList(1, list.size()).toArray());
    }
  }

  @Override
  @SuppressWarnings("unchecked")
  public E get(final int index) {
    final Object[] rest = more; // read once: it also gives the size
    final int size = rest == null ? 0 : rest.length + 1;
    if (index < 0 || index >= size) {
      throw new IndexOutOfBoundsException("Index " + index + " out of bounds for length " + size);
    }
    return index == 0 ? first : (E) rest[index - 1];
  }

  @Override
  public int size() {
    return more == null ? 0 : more.length + 1;
  }

  @Override
  public boolean isEmpty() {
    return more == null;
  }

  @Override
  public Iterator<E> iterator() {
    return new Itr<>(this);
  }

  @Override
  @SuppressWarnings("unchecked")
  public void forEach(final Consumer<? super E> action) {
    final Object[] rest = more;
    if (rest == null) {
      return;
    }
    action.accept(first);
    for (final Object element : rest) {
      action.accept((E) element);
    }
  }

  /** A plain index over the elements: the list is immutable, so there is no modCount to check. */
  private static final class Itr<E> implements Iterator<E> {
    private final CompactList<E> list;
    private final int size;
    private int next;

    Itr(final CompactList<E> list) {
      this.list = list;
      this.size = list.size();
    }

    @Override
    public boolean hasNext() {
      return next < size;
    }

    @Override
    @SuppressWarnings("unchecked")
    public E next() {
      final int index = next;
      if (index >= size) {
        throw new NoSuchElementException();
      }
      next = index + 1;
      return index == 0 ? list.first : (E) list.more[index - 1];
    }
  }
}
