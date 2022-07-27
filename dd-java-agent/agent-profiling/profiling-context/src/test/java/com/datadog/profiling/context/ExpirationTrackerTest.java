package com.datadog.profiling.context;

import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;

class ExpirationTrackerTest {
  private static class ExpirableString implements ExpirationTracker.Expirable<String> {
    final String str;

    ExpirableString(String str) {
      this.str = str;
    }

    @Override
    public ExpirationTracker.Expiring<String> asExpiring() {
      return new ExpirationTracker.Expiring<String>(str, System.nanoTime(), 1_000_000_000L) {
        @Override
        boolean isActive() {
          return true;
        }
      };
    }
  }

  @Test
  void track() throws Exception {
    ExpirationTracker<String, ExpirableString> t =
        new ExpirationTracker<>(100_000, 1_000L, 800_000L);

    for (int i = 0; i < 500_000; i++) {
      t.track(new ExpirableString("delayed " + i));
      LockSupport.parkNanos(5_000L);
    }
  }
}
