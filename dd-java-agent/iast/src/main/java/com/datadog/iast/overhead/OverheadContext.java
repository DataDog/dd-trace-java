package com.datadog.iast.overhead;

import datadog.trace.api.Config;
import java.util.concurrent.atomic.AtomicInteger;

public class OverheadContext {

  private static final int MAX_OPERATIONS = Config.get().getIastMaxContextOperations();

  private final AtomicInteger availableOperations = new AtomicInteger(MAX_OPERATIONS);

  public int getAvailableQuota() {
    return availableOperations.get();
  }

  public boolean consumeQuota(final int delta) {
    final int beforeUpdate =
        availableOperations.getAndAccumulate(delta, (v, d) -> (v < d) ? v : v - d);
    return beforeUpdate >= delta;
  }

  public void reset() {
    availableOperations.set(MAX_OPERATIONS);
  }
}
