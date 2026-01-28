package datadog.metrics.api;

final class NoOpCounter implements Counter {

  public static final Counter NO_OP = new NoOpCounter();

  public void increment(int delta) {}

  public void incrementErrorCount(String cause, int delta) {}
}
