package com.datadog.profiling.jfr;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class CustomTypeBuilder {
  public static final Type SELF_TYPE =
      new BaseType(Long.MIN_VALUE, "", null, null) {
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

  public CustomTypeBuilder addField(String name, Type type) {
    fields.add(new TypedField(name, type));
    return this;
  }

  public CustomTypeBuilder addArrayField(String name, Types.Predefined type) {
    return addArrayField(name, types.getType(type));
  }

  public CustomTypeBuilder addArrayField(String name, Type type) {
    fields.add(new TypedField(name, type, true));
    return this;
  }

  public Type registerType(String name, String supertype, Consumer<CustomTypeBuilder> buildWith) {
    return types.getOrAdd(name, supertype, buildWith);
  }

  public Type registerType(Types.Predefined type, Consumer<CustomTypeBuilder> buildWith) {
    return registerType(type.getTypeName(), buildWith);
  }

  public Type registerType(String name, Consumer<CustomTypeBuilder> buildWith) {
    return types.getOrAdd(name, buildWith);
  }

  List<TypedField> build() {
    return fields;
  }
}
