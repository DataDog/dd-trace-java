package datadog.trace.bootstrap.instrumentation.api;

public interface TaskWrapper {
  static Class<?> getUnwrappedType(Object task) {
    int depth = 0;
    Object inspected = task;
    while (depth < 5 && inspected instanceof TaskWrapper) {
      inspected = ((TaskWrapper) inspected).$$DD$$__unwrap();
      depth++;
    }
    return inspected == null ? null : inspected.getClass();
  }

  Object $$DD$$__unwrap();
}
