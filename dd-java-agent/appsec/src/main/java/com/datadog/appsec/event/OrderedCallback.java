package com.datadog.appsec.event;

import java.util.Comparator;

public interface OrderedCallback {
  int MAXIMUM_PRIORITY = Integer.MIN_VALUE; // the lower the value the earlier it will be processed
  int DEFAULT_PRIORITY = 0;
  int MINIMUM_PRIORITY = Integer.MAX_VALUE;

  int getPriority();

  int getSequenceNumber(); // for resolving draws

  /** Note: not consistent with equals(). */
  enum OrderedCallbackComparator implements Comparator<OrderedCallback> {
    INSTANCE;

    @Override
    public int compare(OrderedCallback o1, OrderedCallback o2) {
      int p1 = o1.getPriority();
      int p2 = o2.getPriority();

      if (p1 < p2) {
        return -1;
      } else if (p1 == p2) {
        return o1.getSequenceNumber() < o2.getSequenceNumber() ? -1 : 1;
      } else {
        return 1;
      }
    }
  }
}
