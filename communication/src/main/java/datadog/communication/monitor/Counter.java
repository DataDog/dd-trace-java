package datadog.communication.monitor;

public interface Counter {

  void increment(int delta);

  void incrementErrorCount(String cause, int delta);
}
