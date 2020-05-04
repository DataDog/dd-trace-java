package com.datadog.profiling.jfr;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Common JFR type super-class */
abstract class BaseJFRType implements JFRType {
  private final long id;
  private final String name;
  private final String supertype;
  private final ConstantPools constantPools;
  private final Types types;
  private final TypedValue nullValue = TypedValue.of(this, (Object) null);

  BaseJFRType(long id, String name, String supertype, ConstantPools constantPools, Types types) {
    this.id = id;
    this.name = name;
    this.supertype = supertype;
    this.constantPools = constantPools;
    this.types = types;
  }

  BaseJFRType(long id, String name, ConstantPools constantPools, Types types) {
    this(id, name, null, constantPools, types);
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
    return isBuiltin() ? TypedValue.of(this, value) : null;
  }

  @Override
  public TypedValue asValue(byte value) {
    return isBuiltin() ? TypedValue.of(this, value) : null;
  }

  @Override
  public TypedValue asValue(char value) {
    return isBuiltin() ? TypedValue.of(this, value) : null;
  }

  @Override
  public TypedValue asValue(short value) {
    return isBuiltin() ? TypedValue.of(this, value) : null;
  }

  @Override
  public TypedValue asValue(int value) {
    return isBuiltin() ? TypedValue.of(this, value) : null;
  }

  @Override
  public TypedValue asValue(long value) {
    return isBuiltin() ? TypedValue.of(this, value) : null;
  }

  @Override
  public TypedValue asValue(float value) {
    return isBuiltin() ? TypedValue.of(this, value) : null;
  }

  @Override
  public TypedValue asValue(double value) {
    return isBuiltin() ? TypedValue.of(this, value) : null;
  }

  @Override
  public TypedValue asValue(boolean value) {
    return isBuiltin() ? TypedValue.of(this, value) : null;
  }

  @Override
  public TypedValue asValue(Consumer<FieldValueBuilder> builderCallback) {
    return isBuiltin() ? null : TypedValue.of(this, builderCallback);
  }

  @Override
  public TypedValue nullValue() {
    return nullValue;
  }

  @Override
  public ConstantPool getConstantPool() {
    return constantPools != null ? constantPools.forType(this) : null;
  }

  @Override
  public Types getTypes() {
    return types;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    BaseJFRType that = (BaseJFRType) o;
    return id == that.id && name.equals(that.name);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, name);
  }
}
