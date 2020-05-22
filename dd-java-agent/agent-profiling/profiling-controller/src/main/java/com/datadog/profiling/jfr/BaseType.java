package com.datadog.profiling.jfr;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import lombok.Generated;

/** Common JFR type super-class */
abstract class BaseType implements Type {
  private final long id;
  private final String name;
  private final String supertype;
  private final ConstantPools constantPools;
  private final Metadata metadata;
  private final AtomicReference<TypedValue> nullValue = new AtomicReference<>();

  BaseType(long id, String name, String supertype, ConstantPools constantPools, Metadata metadata) {
    this.id = id;
    this.name = name;
    this.supertype = supertype;
    this.constantPools = constantPools;
    this.metadata = metadata;
  }

  BaseType(long id, String name, ConstantPools constantPools, Metadata metadata) {
    this(id, name, null, constantPools, metadata);
  }

  @Override
  public long getId() {
    return id;
  }

  @Override
  public final boolean isSimple() {
    List<TypedField> fields = getFields();
    if (fields.size() == 1) {
      TypedField field = fields.get(0);
      return field.getType().isBuiltin() && !field.isArray();
    }
    return false;
  }

  @Override
  public boolean isResolved() {
    return true;
  }

  @Override
  public final String getTypeName() {
    return name;
  }

  @Override
  public boolean hasConstantPool() {
    return constantPools != null;
  }

  @Override
  public final String getSupertype() {
    return supertype;
  }

  @Override
  public TypedValue asValue(String value) {
    return TypedValue.of(this, value);
  }

  @Override
  public TypedValue asValue(byte value) {
    return TypedValue.of(this, value);
  }

  @Override
  public TypedValue asValue(char value) {
    return TypedValue.of(this, value);
  }

  @Override
  public TypedValue asValue(short value) {
    return TypedValue.of(this, value);
  }

  @Override
  public TypedValue asValue(int value) {
    return TypedValue.of(this, value);
  }

  @Override
  public TypedValue asValue(long value) {
    return TypedValue.of(this, value);
  }

  @Override
  public TypedValue asValue(float value) {
    return TypedValue.of(this, value);
  }

  @Override
  public TypedValue asValue(double value) {
    return TypedValue.of(this, value);
  }

  @Override
  public TypedValue asValue(boolean value) {
    return TypedValue.of(this, value);
  }

  @Override
  public TypedValue asValue(Consumer<TypeValueBuilder> builderCallback) {
    if (isBuiltin()) {
      throw new IllegalArgumentException();
    }
    return TypedValue.of(this, builderCallback);
  }

  @Override
  public TypedValue nullValue() {
    return nullValue.updateAndGet(v -> v == null ? new TypedValue(this, null, 0L) : v);
  }

  @Override
  public ConstantPool getConstantPool() {
    return constantPools != null ? constantPools.forType(this) : null;
  }

  @Override
  public Metadata getMetadata() {
    return metadata;
  }

  @Override
  public boolean isUsedBy(Type other) {
    if (other == null) {
      return false;
    }
    return isUsedBy(this, other, new HashSet<>());
  }

  private static boolean isUsedBy(Type type1, Type type2, HashSet<Type> track) {
    if (!track.add(type2)) {
      return false;
    }
    for (TypedField typedField : type2.getFields()) {
      if (typedField.getType().equals(type1)) {
        return true;
      }
      if (isUsedBy(type1, typedField.getType(), track)) {
        return true;
      }
    }
    return false;
  }

  @Generated
  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    BaseType baseType = (BaseType) o;
    return id == baseType.id &&
      name.equals(baseType.name) &&
      Objects.equals(supertype, baseType.supertype);
  }

  @Generated
  @Override
  public int hashCode() {
    return Objects.hash(id, name, supertype);
  }
}
