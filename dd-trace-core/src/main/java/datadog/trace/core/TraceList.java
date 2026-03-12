package datadog.trace.core;

import java.util.ArrayList;

/** ArrayList that exposes modCount to allow for an optimization in TraceInterceptor handling */
final class TraceList extends ArrayList<DDSpan> {
  static final TraceList EMPTY = new TraceList(0);

  static final TraceList of(DDSpan span) {
    TraceList list = new TraceList(1);
    list.add(span);
    return list;
  }

  TraceList(int capacity) {
    super(capacity);
  }

  int modCount() {
    return this.modCount;
  }
}
