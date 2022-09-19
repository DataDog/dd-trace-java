package com.datadog.profiling.leakmonitor;

import javax.management.Notification;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.TabularData;

public class GcMonitor implements NotificationListener {

  private final Analyzer analyzer;
  private final Action action;
  private final Object[][] pools;

  public GcMonitor(Analyzer analyzer, Object[][] pools, Action action) {
    this.analyzer = analyzer;
    this.action = action;
    this.pools = pools;
  }

  @Override
  public void handleNotification(Notification notification, Object handback) {
    CompositeData userData = (CompositeData) notification.getUserData();
    CompositeData gcInfo = (CompositeData) userData.get("gcInfo");
    TabularData memoryUsageAfterGc = (TabularData) gcInfo.get("memoryUsageAfterGc");
    for (Object[] pool : pools) {
      CompositeData data = (CompositeData) memoryUsageAfterGc.get(pool).get("value");
      double signal =
          analyzer.analyze(
              notification.getTimeStamp(),
              (long) data.get("used"),
              (long) data.get("committed"),
              (long) data.get("max"));
      if (signal > 0.9) {
        action.apply();
      } else if (signal < 0) {
        action.revert();
      }
    }
  }
}
