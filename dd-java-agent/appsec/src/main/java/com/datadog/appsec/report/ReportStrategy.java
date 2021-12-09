package com.datadog.appsec.report;

import com.datadog.appsec.report.raw.events.AppSecEvent100;
import com.datadog.appsec.util.JvmTime;
import datadog.trace.api.Config;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;

public interface ReportStrategy {
  boolean shouldFlush();

  boolean shouldFlush(@Nonnull AppSecEvent100 event);

  class Default implements ReportStrategy {
    private static final long MIN_INTERVAL_NANOS =
        TimeUnit.SECONDS.toNanos(Config.get().getAppSecReportMinTimeout());
    private static final long MAX_INTERVAL_NANOS =
        TimeUnit.SECONDS.toNanos(Config.get().getAppSecReportMaxTimeout());
    private static final int MAX_QUEUED_ITEMS = 50;

    private final JvmTime jvmTime;

    private long lastFlush = 0L;
    private int queuedItems = 0;
    private String lastAttackType;

    public Default(JvmTime time) {
      this.jvmTime = time;
    }

    @Override
    public boolean shouldFlush() {
      synchronized (this) {
        if (queuedItems == 0) {
          return false;
        }
        final long curTime = jvmTime.nanoTime();
        boolean flush = queuedItems >= MAX_QUEUED_ITEMS || curTime > lastFlush + MAX_INTERVAL_NANOS;

        if (flush) {
          lastFlush = curTime;
          queuedItems = 0;
        }
        return flush;
      }
    }

    @Override
    public boolean shouldFlush(@Nonnull AppSecEvent100 event) {
      final long curTime = jvmTime.nanoTime();

      synchronized (this) {
        long requiredInterval;
        if (event.getEventType() != null) {
          final String attackType = event.getEventType();

          if (attackType.equals(lastAttackType)) {
            requiredInterval = MAX_INTERVAL_NANOS;
          } else {
            // different attack; use min interval
            requiredInterval = MIN_INTERVAL_NANOS;
            // until next flush, we use the min interval
            lastAttackType = null;
          }
        } else {
          // should not actually happen, because type is mandatory
          requiredInterval = MAX_INTERVAL_NANOS;
        }

        boolean flush = queuedItems >= MAX_QUEUED_ITEMS || curTime > lastFlush + requiredInterval;

        if (flush) {
          lastFlush = curTime;
          queuedItems = 0;
          lastAttackType = event.getEventType();
        } else {
          queuedItems++;
        }

        return flush;
      }
    }
  }
}
