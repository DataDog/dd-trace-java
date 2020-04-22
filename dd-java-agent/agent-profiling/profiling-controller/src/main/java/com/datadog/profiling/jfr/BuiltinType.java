package com.datadog.profiling.jfr;

import java.util.Collections;
import java.util.List;

final class BuiltinType extends BaseType {
  private final Types.Builtin builtin;

  BuiltinType(long id, Types.Builtin type, ConstantPools constantPools, Types types) {
    super(id, type.getTypeName(), constantPools, types);
    this.builtin = type;
  }

  @Override
  public boolean isBuiltin() {
    return true;
  }

  @Override
  public List<TypedField> getFields() {
    return Collections.emptyList();
  }

  @Override
  public boolean canAccept(Object value) {
    if (value == null) {
      // non-initialized built-ins will get the default value and String will be properly handled
      return true;
    }

    if (value instanceof TypedValue) {
      return this == ((TypedValue) value).getType();
    }
    switch (builtin) {
      case STRING:
        {
          return (value instanceof String || value instanceof Long);
        }
      case BYTE:
        {
          return value instanceof Byte;
        }
      case CHAR:
        {
          return value instanceof Character;
        }
      case SHORT:
        {
          return value instanceof Short;
        }
      case INT:
        {
          return value instanceof Integer;
        }
      case LONG:
        {
          return value instanceof Long;
        }
      case FLOAT:
        {
          return value instanceof Float;
        }
      case DOUBLE:
        {
          return value instanceof Double;
        }
      case BOOLEAN:
        {
          return value instanceof Boolean;
        }
    }
    return false;
  }
}
