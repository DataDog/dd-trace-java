package datadog.trace.core.varhandles;

import datadog.trace.unsafe.ConcurrentArrayOperations;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

public final class VarHandleConcurrentArrayOperations implements ConcurrentArrayOperations {

  private static final VarHandle BOOLEAN_ARRAY_VAR_HANDLE =
      MethodHandles.arrayElementVarHandle(boolean[].class);
  private static final VarHandle INT_ARRAY_VAR_HANDLE =
      MethodHandles.arrayElementVarHandle(int[].class);
  private static final VarHandle LONG_ARRAY_VAR_HANDLE =
      MethodHandles.arrayElementVarHandle(long[].class);
  private static final VarHandle OBJECT_ARRAY_VAR_HANDLE =
      MethodHandles.arrayElementVarHandle(Object[].class);

  @Override
  public Object getObjectVolatile(Object[] array, int index) {
    return OBJECT_ARRAY_VAR_HANDLE.getVolatile(array, index);
  }

  @Override
  public void putObjectVolatile(Object[] array, int index, Object x) {
    OBJECT_ARRAY_VAR_HANDLE.setVolatile(array, index, x);
  }

  @Override
  public int getIntVolatile(int[] array, int index) {
    return (int) INT_ARRAY_VAR_HANDLE.getVolatile(array, index);
  }

  @Override
  public void putIntVolatile(int[] array, int index, int x) {
    INT_ARRAY_VAR_HANDLE.setVolatile(array, index, x);
  }

  @Override
  public boolean getBooleanVolatile(boolean[] array, int index) {
    return (boolean) BOOLEAN_ARRAY_VAR_HANDLE.getVolatile(array, index);
  }

  @Override
  public void putBooleanVolatile(boolean[] array, int index, boolean x) {
    BOOLEAN_ARRAY_VAR_HANDLE.setVolatile(array, index, x);
  }

  @Override
  public void putLongVolatile(long[] array, int index, long x) {
    LONG_ARRAY_VAR_HANDLE.setVolatile(array, index, x);
  }

  @Override
  public long getLongVolatile(long[] array, int index) {
    return (long) LONG_ARRAY_VAR_HANDLE.getVolatile(array, index);
  }

  @Override
  public void putOrderedObject(Object[] array, int index, Object x) {
    OBJECT_ARRAY_VAR_HANDLE.setOpaque(array, index, x);
  }

  @Override
  public void putOrderedInt(int[] array, int index, int x) {
    INT_ARRAY_VAR_HANDLE.setOpaque(array, index, x);
  }

  @Override
  public void putOrderedLong(long[] array, int index, long x) {
    LONG_ARRAY_VAR_HANDLE.setOpaque(array, index, x);
  }
}
