package datadog.metrics.api;

public interface Monitoring {
  Monitoring DISABLED = new NoOpMonitoring();

  Recording newTimer(String name);

  Recording newTimer(String name, String... tags);

  Recording newThreadLocalTimer(String name);

  Counter newCounter(String name);
}
