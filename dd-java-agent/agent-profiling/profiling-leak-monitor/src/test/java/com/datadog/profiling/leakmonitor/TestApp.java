package com.datadog.profiling.leakmonitor;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

public class TestApp {

  public static void main(String... args) throws ExecutionException, InterruptedException {
    LiveHeapSizeMonitor monitor =
        new LiveHeapSizeMonitor(
            Analyzers.MOVING_AVERAGE_CROSSOVER.create(),
            new Action() {
              @Override
              public void apply() {
                System.err.println("leak detected");
              }

              @Override
              public void revert() {
                System.err.println("leak fixed");
              }
            });
    Map<LeakingKey, Boolean> leakingMap = new HashMap<>();
    leakingMap.put(new LeakingKey(), true);
    Executors.newSingleThreadExecutor()
        .submit(
            () -> {
              for (long i = 0; i < 10_000_000_000_00L; i++) {
                LeakingKey key = leakingMap.keySet().iterator().next();
                leakingMap.remove(new LeakingKey(key));
                leakingMap.put(new LeakingKey(key), true);
              }
              leakingMap.clear();
              for (long i = 0; i < 10_000_000_000_00L; i++) {
                try {
                  Thread.sleep(1000);
                } catch (InterruptedException e) {
                }
                leakingMap.put(new LeakingKey(), true);
              }
            })
        .get();
  }

  private static class LeakingKey {
    private final int x;
    private final int y;

    public LeakingKey() {
      x = ThreadLocalRandom.current().nextInt();
      y = ThreadLocalRandom.current().nextInt(10);
    }

    public LeakingKey(LeakingKey key) {
      x = ThreadLocalRandom.current().nextInt();
      y = key.y;
    }

    public boolean equals(Object o) {
      if (o instanceof LeakingKey) {
        return y == ((LeakingKey) o).y;
      }
      return false;
    }

    public int hashCode() {
      return x;
    }
  }
}
