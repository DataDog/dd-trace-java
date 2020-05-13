package com.datadog.profiling.jfr;

import java.util.List;
import java.util.function.Consumer;

final class InvalidType implements Type {
  @Override
  public long getId() {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isBuiltin() {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isSimple() {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isResolved() {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean hasConstantPool() {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getSupertype() {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<TypedField> getFields() {
    throw new UnsupportedOperationException();
  }

  @Override
  public TypedField getField(String name) {
    throw new UnsupportedOperationException();
  }

  @Override
  public List<Annotation> getAnnotations() {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean canAccept(Object value) {
    throw new UnsupportedOperationException();
  }

  @Override
  public TypedValue asValue(String value) {
    throw new UnsupportedOperationException();
  }

  @Override
  public TypedValue asValue(byte value) {
    throw new UnsupportedOperationException();
  }

  @Override
  public TypedValue asValue(char value) {
    throw new UnsupportedOperationException();
  }

  @Override
  public TypedValue asValue(short value) {
    throw new UnsupportedOperationException();
  }

  @Override
  public TypedValue asValue(int value) {
    throw new UnsupportedOperationException();
  }

  @Override
  public TypedValue asValue(long value) {
    throw new UnsupportedOperationException();
  }

  @Override
  public TypedValue asValue(float value) {
    throw new UnsupportedOperationException();
  }

  @Override
  public TypedValue asValue(double value) {
    throw new UnsupportedOperationException();
  }

  @Override
  public TypedValue asValue(boolean value) {
    throw new UnsupportedOperationException();
  }

  @Override
  public TypedValue asValue(Consumer<TypeValueBuilder> builderCallback) {
    throw new UnsupportedOperationException();
  }

  @Override
  public TypedValue nullValue() {
    throw new UnsupportedOperationException();
  }

  @Override
  public ConstantPool getConstantPool() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Metadata getMetadata() {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isUsedBy(Type other) {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getTypeName() {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isSame(NamedType other) {
    throw new UnsupportedOperationException();
  }
}
