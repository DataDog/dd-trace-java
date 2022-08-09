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
    final int availableAfter = availableOperations.addAndGet(-delta);
    if (availableAfter < 0) {
      availableOperations.addAndGet(delta);
      return false;
    }
    return true;
  }

  public void reset() {
    availableOperations.set(MAX_OPERATIONS);
  }
}
