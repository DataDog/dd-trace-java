package com.datadog.profiling.jfr;

import java.util.List;
import java.util.function.Consumer;

class ResolvableJFRType implements JFRType {
  private final String typeName;
  private final Metadata metadata;

  private volatile JFRType delegate;

  ResolvableJFRType(String typeName, Metadata metadata) {
    this.typeName = typeName;
    this.metadata = metadata;
  }

  @Override
  public boolean isResolved() {
    return delegate != null;
  }

  private void checkResolved() {
    if (delegate == null) {
      throw new IllegalStateException();
    }
  }

  @Override
  public long getId() {
    checkResolved();
    return delegate.getId();
  }

  @Override
  public boolean hasConstantPool() {
    checkResolved();
    return delegate.hasConstantPool();
  }

  @Override
  public TypedValue asValue(String value) {
    checkResolved();
    return delegate.asValue(value);
  }

  @Override
  public TypedValue asValue(byte value) {
    checkResolved();
    return delegate.asValue(value);
  }

  @Override
  public TypedValue asValue(char value) {
    checkResolved();
    return delegate.asValue(value);
  }

  @Override
  public TypedValue asValue(short value) {
    checkResolved();
    return delegate.asValue(value);
  }

  @Override
  public TypedValue asValue(int value) {
    checkResolved();
    return delegate.asValue(value);
  }

  @Override
  public TypedValue asValue(long value) {
    checkResolved();
    return delegate.asValue(value);
  }

  @Override
  public TypedValue asValue(float value) {
    checkResolved();
    return delegate.asValue(value);
  }

  @Override
  public TypedValue asValue(double value) {
    checkResolved();
    return delegate.asValue(value);
  }

  @Override
  public TypedValue asValue(boolean value) {
    checkResolved();
    return delegate.asValue(value);
  }

  @Override
  public TypedValue asValue(Consumer<FieldValueBuilder> builderCallback) {
    checkResolved();
    return delegate.asValue(builderCallback);
  }

  @Override
  public TypedValue nullValue() {
    checkResolved();
    return delegate.nullValue();
  }

  @Override
  public ConstantPool getConstantPool() {
    checkResolved();
    return delegate.getConstantPool();
  }

  @Override
  public Metadata getMetadata() {
    checkResolved();
    return delegate.getMetadata();
  }

  @Override
  public boolean isBuiltin() {
    checkResolved();
    return delegate.isBuiltin();
  }

  @Override
  public boolean isSimple() {
    checkResolved();
    return delegate.isSimple();
  }

  @Override
  public String getSupertype() {
    checkResolved();
    return delegate.getSupertype();
  }

  @Override
  public List<TypedField> getFields() {
    checkResolved();
    return delegate.getFields();
  }

  @Override
  public List<JFRAnnotation> getAnnotations() {
    checkResolved();
    return delegate.getAnnotations();
  }

  @Override
  public boolean canAccept(Object value) {
    checkResolved();
    return delegate.canAccept(value);
  }

  @Override
  public String getTypeName() {
    return typeName;
  }

  @Override
  public boolean isSame(NamedType other) {
    checkResolved();
    return delegate.isSame(other);
  }

  boolean resolve() {
    JFRType resolved = metadata.getType(typeName, false);
    if (resolved instanceof BaseJFRType) {
      delegate = resolved;
      return true;
    }
    return false;
  }
}
