package datadog.trace.unsafe;

public interface LongCAS {
  boolean compareAndSet(Object holder, long expected, long newValue);
}
