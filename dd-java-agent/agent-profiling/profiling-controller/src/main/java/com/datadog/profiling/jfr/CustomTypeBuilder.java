package com.datadog.profiling.jfr;

import java.util.ArrayList;
import java.util.List;

/** A fluent API for building custom type values lazily. */
public final class CustomTypeBuilder {
  /** A place-holder for fields of the same type as they are defined in. */
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
        public boolean canAccept(Object value) {
          return value == null;
        }
      };

  private final Types types;
  private final List<TypedField> fields = new ArrayList<>();

  CustomTypeBuilder(Types types) {
    this.types = types;
  }

  public CustomTypeBuilder addField(String name, Types.Predefined type) {
    return addField(name, types.getType(type));
  }

  public CustomTypeBuilder addField(String name, JFRType type) {
    fields.add(new TypedField(name, type));
    return this;
  }

  public CustomTypeBuilder addArrayField(String name, Types.Predefined type) {
    return addArrayField(name, types.getType(type));
  }

  public CustomTypeBuilder addArrayField(String name, JFRType type) {
    fields.add(new TypedField(name, type, true));
    return this;
  }

  List<TypedField> build() {
    return fields;
  }
}
