package datadog.trace.core;

import java.util.ArrayList;

/** ArrayList that exposes modCount to allow for an optimization in TraceInterceptor handling */
final class SpanList extends ArrayList<DDSpan> {
  static final SpanList EMPTY = new SpanList(0);

  static final SpanList of(DDSpan span) {
    SpanList list = new SpanList(1);
    list.add(span);
    return list;
  }

  SpanList(int capacity) {
    super(capacity);
  }

  int modCount() {
    return this.modCount;
  }
}
