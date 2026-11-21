package datadog.trace.util;

import java.util.AbstractList;
import java.util.Collection;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.RandomAccess;
import java.util.function.Consumer;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * An immutable list stored at its exact size, for small lists held in long-lived state: one shared
 * empty list, a single object for one element, and the object plus one exact-size array for more.
 * Elements may be {@code null}.
 *
 * <p>Long-lived state, such as a parsed configuration, stays in the old generation and every
 * marking cycle walks it. When most of its lists hold zero or one element, an {@code ArrayList} and
 * its array each is mostly overhead.
 *
 * <p>Walking one allocates nothing, as long as the walk's objects are short-lived:
 *
 * <ul>
 *   <li>Use one list class for a whole structure. A loop that sees several list classes (say {@code
 *       emptyList}, {@code singletonList} and {@code ArrayList}) goes megamorphic, and escape
 *       analysis keeps its iterators.
 *   <li>The iterator, {@code forEach} and {@code isEmpty} are this class's own, not {@code
 *       AbstractList}'s. That code is shared by every {@code AbstractList} subclass, so in an
 *       application its calls go megamorphic and its iterator escapes.
 *   <li>{@link #iterator()} and {@link #cursor()} return a new object on every path, the empty list
 *       included. A shared one on any path would make the result a merged allocation, which escape
 *       analysis can't remove.
 * </ul>
 *
 * <p>Use {@link #forEach} to visit every element, a for-each loop where an {@code Iterator} is
 * needed, and {@link #cursor()} for a walk that stops early or carries state in locals.
 *
 * @param <E> the element type
 */
public final class CompactList<E> extends AbstractList<E> implements RandomAccess {
  private static final Object[] NO_MORE = new Object[0];
  // The only list whose `more` is null, so the size needs no field of its own.
  private static final CompactList<Object> EMPTY = new CompactList<>(null, null);

  private final E first;
  // The elements after the first: null for the empty list, NO_MORE for one element.
  private final Object[] more;

  private CompactList(final E first, final Object[] more) {
    this.first = first;
    this.more = more;
  }

  /**
   * Gets the empty list, which is shared.
   *
   * @param <E> the element type
   * @return the empty list.
   */
  @SuppressWarnings("unchecked")
  @Nonnull
  public static <E> CompactList<E> empty() {
    return (CompactList<E>) EMPTY;
  }

  /**
   * Copies a collection, in its iteration order, at its exact size.
   *
   * @param <E> the element type
   * @param elements the elements; {@code null} elements are kept.
   * @return a compact list of the elements, or the shared empty list.
   */
  @SuppressWarnings("unchecked")
  @Nonnull
  public static <E> CompactList<E> copyOf(@Nonnull final Collection<? extends E> elements) {
    if (elements instanceof CompactList) {
      return (CompactList<E>) elements;
    }
    final Object[] all = elements.toArray();
    switch (all.length) {
      case 0:
        return empty();
      case 1:
        return new CompactList<>((E) all[0], NO_MORE);
      default:
        final Object[] rest = new Object[all.length - 1];
        System.arraycopy(all, 1, rest, 0, rest.length);
        return new CompactList<>((E) all[0], rest);
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
  @Nonnull
  public Iterator<E> iterator() {
    return new Itr<>(this);
  }

  /**
   * Walks the elements in order without an {@code Iterator}'s look-ahead. {@code null} marks the
   * end, so use it only on a list without {@code null} elements.
   *
   * @return a new cursor over this list.
   */
  @Nonnull
  public Cursor<E> cursor() {
    return new Cur<>(this);
  }

  @Override
  @SuppressWarnings("unchecked")
  public void forEach(@Nonnull final Consumer<? super E> action) {
    final Object[] rest = more;
    if (rest == null) {
      return;
    }
    action.accept(first);
    for (final Object element : rest) {
      action.accept((E) element);
    }
  }

  /** The index walk the iterator and the cursor share; the list is immutable, so no modCount. */
  private abstract static class Walk<E> {
    private final CompactList<E> list;
    final int size;
    int next;

    Walk(final CompactList<E> list) {
      this.list = list;
      this.size = list.size();
    }

    @SuppressWarnings("unchecked")
    final E elementAt(final int index) {
      return index == 0 ? list.first : (E) list.more[index - 1];
    }
  }

  private static final class Itr<E> extends Walk<E> implements Iterator<E> {
    Itr(final CompactList<E> list) {
      super(list);
    }

    @Override
    public boolean hasNext() {
      return next < size;
    }

    @Override
    public E next() {
      final int index = next;
      if (index >= size) {
        throw new NoSuchElementException();
      }
      next = index + 1;
      return elementAt(index);
    }
  }

  private static final class Cur<E> extends Walk<E> implements Cursor<E> {
    Cur(final CompactList<E> list) {
      super(list);
    }

    @Override
    @Nullable
    public E next() {
      final int index = next;
      if (index >= size) {
        return null;
      }
      next = index + 1;
      return elementAt(index);
    }
  }
}
