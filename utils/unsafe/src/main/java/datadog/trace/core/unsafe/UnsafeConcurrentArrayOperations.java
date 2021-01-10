package datadog.trace.core.unsafe;

import static datadog.trace.core.unsafe.UnsafeAccess.UNSAFE;
import static datadog.trace.core.unsafe.UnsafeAccess.booleanArrayIndex;
import static datadog.trace.core.unsafe.UnsafeAccess.intArrayIndex;
import static datadog.trace.core.unsafe.UnsafeAccess.longArrayIndex;
import static datadog.trace.core.unsafe.UnsafeAccess.objectArrayIndex;

import datadog.trace.unsafe.ConcurrentArrayOperations;

public final class UnsafeConcurrentArrayOperations implements ConcurrentArrayOperations {

  @Override
  public Object getObjectVolatile(Object[] array, int index) {
    return UNSAFE.getObjectVolatile(array, objectArrayIndex(index));
  }

  @Override
  public void putObjectVolatile(Object[] array, int index, Object x) {
    UNSAFE.putObjectVolatile(array, objectArrayIndex(index), x);
  }

  @Override
  public int getIntVolatile(int[] array, int index) {
    return UNSAFE.getIntVolatile(array, intArrayIndex(index));
  }

  @Override
  public long getLongVolatile(long[] array, int index) {
    return UNSAFE.getLongVolatile(array, longArrayIndex(index));
  }

  @Override
  public void putIntVolatile(int[] array, int index, int x) {
    UNSAFE.putIntVolatile(array, intArrayIndex(index), x);
  }

  @Override
  public boolean getBooleanVolatile(boolean[] array, int index) {
    return UNSAFE.getBooleanVolatile(array, booleanArrayIndex(index));
  }

  @Override
  public void putBooleanVolatile(boolean[] array, int index, boolean x) {
    UNSAFE.putBooleanVolatile(array, booleanArrayIndex(index), x);
  }

  @Override
  public void putLongVolatile(long[] array, int index, long x) {
    UNSAFE.putLongVolatile(array, longArrayIndex(index), x);
  }

  @Override
  public void putOrderedObject(Object[] array, int index, Object x) {
    UNSAFE.putOrderedObject(array, objectArrayIndex(index), x);
  }

  @Override
  public void putOrderedInt(int[] array, int index, int x) {
    UNSAFE.putOrderedInt(array, intArrayIndex(index), x);
  }

  @Override
  public void putOrderedLong(long[] array, int index, long x) {
    UNSAFE.putOrderedLong(array, longArrayIndex(index), x);
  }
}
