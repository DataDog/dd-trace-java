package datadog.trace.core;

import java.util.ArrayList;

/** ArrayList that exposes modCount to allow for an optimization in TraceInterceptor handling */
final class SpanList extends ArrayList<DDSpan> {
  static final SpanList EMPTY = new SpanList(0);

  /**
   * Convenience function for creating a SpanList containing a single DDSpan. Meant as replacement
   * for Collections.singletonList when creating a SpanList.
   *
   * @param span != null
   * @return a SpanList
   */
  static final SpanList of(DDSpan span) {
    SpanList list = new SpanList(1);
    list.add(span);
    return list;
  }

  /**
   * Constructs a SpanList with the specified capacity
   *
   * @param capacity
   */
  SpanList(int capacity) {
    super(capacity);
  }

  /** The modifcation count of the List - can be used to check if the List has been altered */
  int modCount() {
    return this.modCount;
  }
}
