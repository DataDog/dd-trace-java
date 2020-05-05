package com.datadog.profiling.jfr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** A custom JFR type */
final class CustomJFRType extends BaseJFRType {
  private final List<TypedField> fields;
  private final List<JFRAnnotation> annotations;

  CustomJFRType(
      long id,
      String name,
      String supertype,
      TypeStructure typeStructure,
      ConstantPools constantPools,
      Types types) {
    super(id, name, supertype, constantPools, types);
    this.fields = Collections.unmodifiableList(typeStructure.fields.stream().map(field -> field.getType() == CustomTypeBuilder.SELF_TYPE ? new TypedField(field.getName(), this, field.isArray()) : field).collect(Collectors.toList()));
    this.annotations = Collections.unmodifiableList(typeStructure.annotations);
  }

  @Override
  public boolean isBuiltin() {
    return false;
  }

  @Override
  public List<TypedField> getFields() {
    return fields;
  }

  @Override
  public List<JFRAnnotation> getAnnotations() {
    return annotations;
  }

  @Override
  public boolean canAccept(Object value) {
    if (value == null) {
      return true;
    }
    if (value instanceof TypedValue) {
      return ((TypedValue) value).getType().equals(this);
    }
    return value instanceof Map;
  }
}
