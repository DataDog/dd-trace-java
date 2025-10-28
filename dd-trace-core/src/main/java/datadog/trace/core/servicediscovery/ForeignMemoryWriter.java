package datadog.trace.core.servicediscovery;

@FunctionalInterface
public interface ForeignMemoryWriter {
  void write(String fileName, byte[] payload);
}
