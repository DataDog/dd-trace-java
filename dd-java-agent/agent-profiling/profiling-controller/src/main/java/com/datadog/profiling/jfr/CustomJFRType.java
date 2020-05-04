package com.datadog.profiling.jfr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A custom JFR type */
final class CustomJFRType extends BaseJFRType {
  private final List<TypedField> fields;

  CustomJFRType(
      long id,
      String name,
      String supertype,
      List<TypedField> fieldStructure,
      ConstantPools constantPools,
      Types types) {
    super(id, name, supertype, constantPools, types);
    Map<String, TypedField> fieldMap = new HashMap<>();
    for (TypedField field : fieldStructure) {
      fieldMap.put(
          field.getName(),
          field.getType() == CustomTypeBuilder.SELF_TYPE
              ? new TypedField(field.getName(), this, field.isArray())
              : field);
    }
    this.fields = new ArrayList<>(fieldMap.values());
  }

  @Override
  public boolean isBuiltin() {
    return false;
  }

  @Override
  public List<TypedField> getFields() {
    return fields == null ? Collections.emptyList() : Collections.unmodifiableList(fields);
  }

  @Override
  public boolean canAccept(Object value) {
    if (value == null) {
      return true;
    }
    //    if (value instanceof Long && hasConstantPool()) {
    //      return true;
    //    }
    if (value instanceof TypedValue) {
      return ((TypedValue) value).getType().equals(this);
    }
    return value instanceof Map;
  }
}
