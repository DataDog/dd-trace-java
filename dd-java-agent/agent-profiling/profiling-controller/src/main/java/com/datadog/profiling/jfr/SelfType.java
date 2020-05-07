package com.datadog.profiling.jfr;

import java.util.List;

/**
 * A place-holder for fields of the same type as they are defined in. Keeping the default values
 * intentionally 'wrong' in order for things to break soon if this type is not replaced by the
 * concrete type counterpart correctly.
 */
final class SelfType extends BaseType {
  static final SelfType INSTANCE = new SelfType();

  private SelfType() {
    super(Long.MIN_VALUE, "", null, null);
  }

  @Override
  public boolean isBuiltin() {
    return false;
  }

  @Override
  public List<TypedField> getFields() {
    return null;
  }

  @Override
  public TypedField getField(String name) {
    return null;
  }

  @Override
  public List<Annotation> getAnnotations() {
    return null;
  }

  @Override
  public boolean canAccept(Object value) {
    return value == null;
  }
}
