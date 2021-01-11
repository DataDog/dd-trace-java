package datadog.trace.core.unsafe;

import datadog.trace.unsafe.CASFactory;
import datadog.trace.unsafe.IntCAS;
import datadog.trace.unsafe.LongCAS;
import datadog.trace.unsafe.ReferenceCAS;
import java.lang.reflect.Field;

public class UnsafeCASFactory implements CASFactory {
  @Override
  public <T> ReferenceCAS<T> createReferenceCAS(
      Class<?> type, String fieldName, Class<T> fieldType) {
    return createUnsafeCAS(type, fieldName, fieldType);
  }

  @Override
  public LongCAS createLongCAS(Class<?> type, String fieldName) {
    return createUnsafeCAS(type, fieldName, long.class);
  }

  @Override
  public IntCAS createIntCAS(Class<?> type, String fieldName) {
    return createUnsafeCAS(type, fieldName, int.class);
  }

  private <T> UnsafeCAS<T> createUnsafeCAS(Class<?> type, String fieldName, Class<T> fieldType) {
    try {
      Field field = type.getDeclaredField(fieldName);
      if (field.getType() != fieldType) {
        throw new IllegalArgumentException(
            "Trying to CAS field type: " + field.getType() + " as: " + fieldType);
      }
      return new UnsafeCAS<>(type.getDeclaredField(fieldName));
    } catch (NoSuchFieldException e) {
      // expected to be used as a static field initializer, so the
      // failure to load the calling class would come out in testing
      throw new IllegalArgumentException(e);
    }
  }
}
