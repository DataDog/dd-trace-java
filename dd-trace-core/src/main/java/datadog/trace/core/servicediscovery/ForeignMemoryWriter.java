package datadog.trace.core.servicediscovery;

@FunctionalInterface
public interface ForeignMemoryWriter {
  void write(byte[] payload);
}
