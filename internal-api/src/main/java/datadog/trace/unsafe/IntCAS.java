package datadog.trace.unsafe;

public interface IntCAS {
  boolean compareAndSet(Object holder, int expected, int newValue);
}
