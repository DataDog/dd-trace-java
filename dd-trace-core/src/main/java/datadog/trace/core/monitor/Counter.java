package datadog.trace.core.monitor;

public interface Counter {

  void increment(int delta);

  void incrementErrorCount(String cause, int delta);
}
