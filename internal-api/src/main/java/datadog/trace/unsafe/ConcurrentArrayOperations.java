package datadog.trace.unsafe;

public interface ConcurrentArrayOperations {

  Object getObjectVolatile(Object[] array, int index);

  void putObjectVolatile(Object[] array, int index, Object x);

  int getIntVolatile(int[] array, int index);

  void putIntVolatile(int[] array, int index, int x);

  boolean getBooleanVolatile(boolean[] array, int index);

  void putBooleanVolatile(boolean[] array, int index, boolean x);

  long getLongVolatile(long[] array, int index);

  void putLongVolatile(long[] array, int index, long x);

  void putOrderedObject(Object[] array, int index, Object x);

  void putOrderedInt(int[] array, int index, int x);

  void putOrderedLong(long[] array, int index, long x);
}
