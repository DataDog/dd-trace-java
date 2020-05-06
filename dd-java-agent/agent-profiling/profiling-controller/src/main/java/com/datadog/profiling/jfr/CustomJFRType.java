package com.datadog.profiling.jfr;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** A custom JFR type */
final class CustomJFRType extends BaseJFRType {
  /**
   * A place-holder for fields of the same type as they are defined in. Keeping the default values
   * intentionally 'wrong' in order for things to break soon if this type is not replaced by the
   * concrete type counterpart correctly.
   */
  public static final JFRType SELF_TYPE =
      new BaseJFRType(Long.MIN_VALUE, "", null, null) {
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
        public List<JFRAnnotation> getAnnotations() {
          return null;
        }

        @Override
        public boolean canAccept(Object value) {
          return value == null;
        }
      };

  private final Map<String, TypedField> fieldMap;
  private final List<TypedField> fields;
  private final List<JFRAnnotation> annotations;

  CustomJFRType(
      long id,
      String name,
      String supertype,
      TypeStructure typeStructure,
      ConstantPools constantPools,
      Metadata metadata) {
    super(id, name, supertype, constantPools, metadata);
    this.fields =
        typeStructure == null
            ? Collections.emptyList()
            : Collections.unmodifiableList(
                typeStructure
                    .fields
                    .stream()
                    .map(
                        field ->
                            field.getType() == SELF_TYPE
                                ? new TypedField(this, field.getName(), field.isArray())
                                : field)
                    .collect(Collectors.toList()));
    this.annotations =
        typeStructure == null
            ? Collections.emptyList()
            : Collections.unmodifiableList(typeStructure.annotations);
    this.fieldMap = fields.stream().collect(Collectors.toMap(TypedField::getName, f -> f));
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
  public TypedField getField(String name) {
    return fieldMap.get(name);
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
