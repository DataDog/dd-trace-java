package datadog.trace.api.profiling;

public interface TransientProfilingContextHolder {
  void storeContextToTag();
}
