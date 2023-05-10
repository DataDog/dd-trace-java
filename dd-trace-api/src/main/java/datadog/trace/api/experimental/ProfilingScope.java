package datadog.trace.api.experimental;

public interface ProfilingScope extends AutoCloseable, ProfilingContext {

  ProfilingScope NO_OP = () -> {};

  @Override
  void close();
}
