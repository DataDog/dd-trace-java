package datadog.trace.core.histogram;

public interface Histogram {

  void accept(long value);

  byte[] serialize();
}
