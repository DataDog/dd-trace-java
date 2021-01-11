package datadog.trace.core.varhandles;

import datadog.trace.unsafe.IntCAS;
import datadog.trace.unsafe.LongCAS;
import datadog.trace.unsafe.ReferenceCAS;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

public final class VarHandleCAS<T> implements ReferenceCAS<T>, LongCAS, IntCAS {

  private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

  private final VarHandle varHandle;

  public VarHandleCAS(Class<?> type, String fieldName, Class<T> fieldType)
      throws NoSuchFieldException, IllegalAccessException {
    this.varHandle =
        MethodHandles.privateLookupIn(type, LOOKUP).findVarHandle(type, fieldName, fieldType);
  }

  @Override
  public boolean compareAndSet(Object holder, Object expected, Object newValue) {
    return varHandle.compareAndSet(holder, expected, newValue);
  }

  @Override
  public boolean compareAndSet(Object holder, int expected, int newValue) {
    return varHandle.compareAndSet(holder, expected, newValue);
  }

  @Override
  public boolean compareAndSet(Object holder, long expected, long newValue) {
    return varHandle.compareAndSet(holder, expected, newValue);
  }
}
