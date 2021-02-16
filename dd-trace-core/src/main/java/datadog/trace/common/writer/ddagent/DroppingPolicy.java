package datadog.trace.common.writer.ddagent;

public interface DroppingPolicy {
  boolean active();
}
