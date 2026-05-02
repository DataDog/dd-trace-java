package datadog.metrics.api;

public interface Counter {

  void increment(int delta);

  void incrementErrorCount(String cause, int delta);
}
