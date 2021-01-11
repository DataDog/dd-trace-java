package datadog.trace.unsafe;

public interface CASFactory {

  <T> ReferenceCAS<T> createReferenceCAS(Class<?> type, String fieldName, Class<T> fieldType);

  LongCAS createLongCAS(Class<?> type, String fieldName);

  IntCAS createIntCAS(Class<?> type, String fieldName);
}
