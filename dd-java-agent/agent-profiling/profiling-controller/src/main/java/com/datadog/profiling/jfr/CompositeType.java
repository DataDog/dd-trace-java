package com.datadog.profiling.jfr;

import com.datadog.profiling.util.NonZeroHashCode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.Generated;

/** A composite JFR type */
final class CompositeType extends BaseType {
  private int hashCode = 0;

  private final Map<String, TypedField> fieldMap;
  private final List<TypedField> fields;
  private final List<Annotation> annotations;

  CompositeType(
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
                            field.getType() == SelfType.INSTANCE
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
  public List<Annotation> getAnnotations() {
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

  // use Lombok @Generated to skip jacoco coverage verification
  @Generated
  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    if (!super.equals(o)) {
      return false;
    }
    CompositeType that = (CompositeType) o;
    return fields.equals(that.fields) && annotations.equals(that.annotations);
  }

  // use Lombok @Generated to skip jacoco coverage verification
  @Generated
  @Override
  public int hashCode() {
    if (hashCode == 0) {
      List<TypedField> nonRecursiveFields = new ArrayList<>(fields.size());
      for (TypedField typedField : fields) {
        if (typedField.getType() != this) {
          nonRecursiveFields.add(typedField);
        }
      }
      hashCode = NonZeroHashCode.hash(super.hashCode(), nonRecursiveFields, annotations);
    }
    return hashCode;
  }
}
