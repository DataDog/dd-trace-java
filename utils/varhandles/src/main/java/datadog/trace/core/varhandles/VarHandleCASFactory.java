package datadog.trace.core.varhandles;

import datadog.trace.unsafe.CASFactory;
import datadog.trace.unsafe.IntCAS;
import datadog.trace.unsafe.LongCAS;
import datadog.trace.unsafe.ReferenceCAS;

public final class VarHandleCASFactory implements CASFactory {
  @Override
  public <T> ReferenceCAS<T> createReferenceCAS(
      Class<?> type, String fieldName, Class<T> fieldType) {
    return createVarHandleCAS(type, fieldName, fieldType);
  }

  @Override
  public LongCAS createLongCAS(Class<?> type, String fieldName) {
    return createVarHandleCAS(type, fieldName, long.class);
  }

  @Override
  public IntCAS createIntCAS(Class<?> type, String fieldName) {
    return createVarHandleCAS(type, fieldName, int.class);
  }

  private <T> VarHandleCAS<T> createVarHandleCAS(
      Class<?> type, String fieldName, Class<T> fieldType) {
    try {
      return new VarHandleCAS<>(type, fieldName, fieldType);
    } catch (Throwable error) {
      throw new IllegalArgumentException(error);
    }
  }
}
