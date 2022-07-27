package com.datadog.profiling.context;

public class ExpirableString implements ExpirationTracker.Expirable<String> {
  private static class ExpiringString extends ExpirationTracker.Expiring<String> {
    public ExpiringString(String payload) {
      super(payload, System.nanoTime(), 500_000_000L);
    }

    @Override
    boolean isActive() {
      return true;
    }
  }

  private final String str;

  public ExpirableString(String str) {
    this.str = str;
  }

  @Override
  public ExpirationTracker.Expiring<String> asExpiring() {
    return new ExpiringString(str);
  }
}
