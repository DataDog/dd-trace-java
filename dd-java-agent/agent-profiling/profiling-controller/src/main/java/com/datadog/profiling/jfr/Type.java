package com.datadog.profiling.jfr;

import java.util.List;
import java.util.function.Consumer;

public interface Type extends NamedType {
  long getId();

  boolean isBuiltin();

  boolean isSimple();

  boolean hasConstantPool();

  String getSupertype();

  List<TypedField> getFields();

  boolean canAccept(Object value);

  TypedValue asValue(String value);

  TypedValue asValue(byte value);

  TypedValue asValue(char value);

  TypedValue asValue(short value);

  TypedValue asValue(int value);

  TypedValue asValue(long value);

  TypedValue asValue(float value);

  TypedValue asValue(double value);

  TypedValue asValue(boolean value);

  TypedValue asValue(Consumer<FieldValueBuilder> fieldAccess);

  TypedValue nullValue();

  ConstantPool getConstantPool();

  Types getTypes();
}
