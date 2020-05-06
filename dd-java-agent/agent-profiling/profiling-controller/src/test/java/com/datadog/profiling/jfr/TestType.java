package com.datadog.profiling.jfr;

import java.util.List;

abstract class TestType extends BaseJFRType {
  public TestType(
      long id, String name, String supertype, ConstantPools constantPools, Metadata metadata) {
    super(id, name, supertype, constantPools, metadata);
  }

  @Override
  public boolean isBuiltin() {
    return false;
  }

  @Override
  public List<TypedField> getFields() {
    throw new IllegalArgumentException();
  }

  @Override
  public TypedField getField(String name) {
    throw new IllegalArgumentException();
  }

  @Override
  public List<JFRAnnotation> getAnnotations() {
    throw new IllegalArgumentException();
  }

  @Override
  public boolean canAccept(Object value) {
    return false;
  }
}
