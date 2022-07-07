package datadog.trace.api.profiling;

public interface TransientProfilingContextHolder {
  int storeContextToTag();
}
