package datadog.trace.core.servicediscovery;

public interface ForeignMemoryWriter {
  void write(byte[] payload);
}
