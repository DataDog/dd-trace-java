package com.datadog.appsec.event;

import java.util.Comparator;

public interface OrderedCallback {
  enum Priority {
    // ordinal is important
    // do not add more because EventDispatcher relies on ordinals
    // fitting into 2 bits
    HIGHEST,
    HIGH,
    DEFAULT,
    LOW
  }

  Priority getPriority();

  /* Note: not consistent with equals(). */
  final class CallbackPriorityComparator implements Comparator<OrderedCallback> {
    private CallbackPriorityComparator() {}

    public static final CallbackPriorityComparator INSTANCE = new CallbackPriorityComparator();

    @Override
    public int compare(OrderedCallback o1, OrderedCallback o2) {
      int p1 = o1.getPriority().ordinal();
      int p2 = o2.getPriority().ordinal();

      if (p1 < p2) {
        return -1;
      } else if (p1 == p2) {
        return 0;
      } else {
        return 1;
      }
    }
  }
}
