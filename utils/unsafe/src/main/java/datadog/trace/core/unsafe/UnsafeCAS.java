package datadog.trace.core.unsafe;

import static datadog.trace.core.unsafe.UnsafeAccess.UNSAFE;

import datadog.trace.unsafe.IntCAS;
import datadog.trace.unsafe.LongCAS;
import datadog.trace.unsafe.ReferenceCAS;
import java.lang.reflect.Field;

public final class UnsafeCAS<T> implements ReferenceCAS<T>, IntCAS, LongCAS {

  private final long offset;

  public UnsafeCAS(Field field) {
    this.offset = UNSAFE.objectFieldOffset(field);
  }

  @Override
  public boolean compareAndSet(Object holder, int expected, int newValue) {
    return UNSAFE.compareAndSwapInt(holder, offset, expected, newValue);
  }

  @Override
  public boolean compareAndSet(Object holder, long expected, long newValue) {
    return UNSAFE.compareAndSwapLong(holder, offset, expected, newValue);
  }

  @Override
  public boolean compareAndSet(Object holder, T expected, T newValue) {
    return UNSAFE.compareAndSwapObject(holder, offset, expected, newValue);
  }
}
