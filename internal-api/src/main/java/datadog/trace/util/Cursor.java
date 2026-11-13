package datadog.trace.util;

import javax.annotation.Nullable;

/**
 * Walks a sequence one element at a time: {@link #next} advances and returns the next element, or
 * {@code null} when there are none left. So a cursor can't produce {@code null} elements.
 *
 * <pre>{@code
 * for (E e = cursor.next(); e != null; e = cursor.next()) {
 *   ...
 * }
 * }</pre>
 *
 * <p>Prefer a cursor to an {@link java.util.Iterator} when writing a traversal. One method that
 * advances and answers needs no look-ahead: an {@code Iterator} has to compute the next element in
 * {@code hasNext()} and hold it until {@code next()}, so its implementations carry a look-ahead
 * field, an end flag, and priming in the constructor. A cursor keeps only the position.
 *
 * <p>Where a caller also needs an {@code Iterator} (a for-each loop, a JDK API), keep the
 * look-ahead in the iterator, not the cursor. On a hot path, share the traversal through a base
 * class that both the cursor and the iterator extend, rather than wrapping a cursor in an iterator:
 * a wrapper is a second object whose fields change in the caller's loop, and C2 (JDK 17) didn't
 * scalar-replace that pair even with everything inlined, so each walk allocated the cursor (see
 * {@code FlatHashtable}). A wrapper is fine off the hot path; give each producer its own, since a
 * shared wrapper's {@code cursor.next()} sees every cursor type and goes megamorphic.
 *
 * <p>A cursor may return a fresh object per element or reuse one (a flyweight). Which is cheaper
 * depends on whether escape analysis removes the fresh objects, so measure; and a cursor that
 * reuses an element type must say that its elements can't be kept.
 *
 * @param <T> the element type
 */
public interface Cursor<T> {
  /**
   * Advances to the next element.
   *
   * @return the next element, or null when there are none left
   */
  @Nullable
  T next();
}
