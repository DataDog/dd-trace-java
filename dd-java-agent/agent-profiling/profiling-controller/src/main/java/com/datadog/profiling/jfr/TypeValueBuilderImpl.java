package com.datadog.profiling.jfr;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

final class TypeValueBuilderImpl implements TypeValueBuilder {
  private final Metadata metadata;
  private final Map<String, TypedField> fieldMap;
  private final Map<String, TypedFieldValue> fieldValueMap;

  TypeValueBuilderImpl(Type type) {
    this.metadata = type.getMetadata();
    fieldMap =
        type.getFields()
            .stream()
            .collect(Collectors.toMap(TypedField::getName, typeField -> typeField));
    fieldValueMap = new HashMap<>();
  }

  @Override
  public TypeValueBuilderImpl putField(String name, byte value) {
    return putField(name, TypedValue.of(metadata.getType(Types.Builtin.BYTE, false), value));
  }

  @Override
  public TypeValueBuilderImpl putField(String name, byte[] values) {
    putArrayField(
        name,
        () -> {
          Type type = metadata.getType(Types.Builtin.BYTE, false);
          TypedValue[] typedValues = new TypedValue[values.length];
          for (int i = 0; i < values.length; i++) {
            typedValues[i] = TypedValue.of(type, values[i]);
          }
          return typedValues;
        });

    return this;
  }

  @Override
  public TypeValueBuilderImpl putField(String name, char value) {
    return putField(name, TypedValue.of(metadata.getType(Types.Builtin.CHAR, false), value));
  }

  @Override
  public TypeValueBuilderImpl putField(String name, char[] values) {
    putArrayField(
        name,
        () -> {
          Type type = metadata.getType(Types.Builtin.CHAR, false);
          TypedValue[] typedValues = new TypedValue[values.length];
          for (int i = 0; i < values.length; i++) {
            typedValues[i] = TypedValue.of(type, values[i]);
          }
          return typedValues;
        });

    return this;
  }

  @Override
  public TypeValueBuilderImpl putField(String name, short value) {
    return putField(name, TypedValue.of(metadata.getType(Types.Builtin.SHORT, false), value));
  }

  @Override
  public TypeValueBuilderImpl putField(String name, short[] values) {
    putArrayField(
        name,
        () -> {
          Type type = metadata.getType(Types.Builtin.SHORT, false);
          TypedValue[] typedValues = new TypedValue[values.length];
          for (int i = 0; i < values.length; i++) {
            typedValues[i] = TypedValue.of(type, values[i]);
          }
          return typedValues;
        });

    return this;
  }

  @Override
  public TypeValueBuilderImpl putField(String name, int value) {
    return putField(name, TypedValue.of(metadata.getType(Types.Builtin.INT, false), value));
  }

  @Override
  public TypeValueBuilderImpl putField(String name, int[] values) {
    putArrayField(
        name,
        () -> {
          Type type = metadata.getType(Types.Builtin.INT, false);
          TypedValue[] typedValues = new TypedValue[values.length];
          for (int i = 0; i < values.length; i++) {
            typedValues[i] = TypedValue.of(type, values[i]);
          }
          return typedValues;
        });

    return this;
  }

  @Override
  public TypeValueBuilderImpl putField(String name, long value) {
    return putField(name, TypedValue.of(metadata.getType(Types.Builtin.LONG, false), value));
  }

  @Override
  public TypeValueBuilderImpl putField(String name, long[] values) {
    putArrayField(
        name,
        () -> {
          Type type = metadata.getType(Types.Builtin.LONG, false);
          TypedValue[] typedValues = new TypedValue[values.length];
          for (int i = 0; i < values.length; i++) {
            typedValues[i] = TypedValue.of(type, values[i]);
          }
          return typedValues;
        });

    return this;
  }

  @Override
  public TypeValueBuilderImpl putField(String name, float value) {
    return putField(name, TypedValue.of(metadata.getType(Types.Builtin.FLOAT, false), value));
  }

  @Override
  public TypeValueBuilderImpl putField(String name, float[] values) {
    putArrayField(
        name,
        () -> {
          Type type = metadata.getType(Types.Builtin.FLOAT, false);
          TypedValue[] typedValues = new TypedValue[values.length];
          for (int i = 0; i < values.length; i++) {
            typedValues[i] = TypedValue.of(type, values[i]);
          }
          return typedValues;
        });

    return this;
  }

  @Override
  public TypeValueBuilderImpl putField(String name, double value) {
    return putField(name, TypedValue.of(metadata.getType(Types.Builtin.DOUBLE, false), value));
  }

  @Override
  public TypeValueBuilderImpl putField(String name, double[] values) {
    putArrayField(
        name,
        () -> {
          Type type = metadata.getType(Types.Builtin.DOUBLE, false);
          TypedValue[] typedValues = new TypedValue[values.length];
          for (int i = 0; i < values.length; i++) {
            typedValues[i] = TypedValue.of(type, values[i]);
          }
          return typedValues;
        });

    return this;
  }

  @Override
  public TypeValueBuilderImpl putField(String name, boolean value) {
    return putField(name, TypedValue.of(metadata.getType(Types.Builtin.BOOLEAN, false), value));
  }

  @Override
  public TypeValueBuilderImpl putField(String name, boolean[] values) {
    putArrayField(
        name,
        () -> {
          Type type = metadata.getType(Types.Builtin.BOOLEAN, false);
          TypedValue[] typedValues = new TypedValue[values.length];
          for (int i = 0; i < values.length; i++) {
            typedValues[i] = TypedValue.of(type, values[i]);
          }
          return typedValues;
        });

    return this;
  }

  @Override
  public TypeValueBuilderImpl putField(String name, String value) {
    return putField(name, TypedValue.of(metadata.getType(Types.Builtin.STRING, false), value));
  }

  @Override
  public TypeValueBuilderImpl putField(String name, String[] values) {
    putArrayField(
        name,
        () -> {
          Type type = metadata.getType(Types.Builtin.STRING, false);
          TypedValue[] typedValues = new TypedValue[values.length];
          for (int i = 0; i < values.length; i++) {
            typedValues[i] = TypedValue.of(type, values[i]);
          }
          return typedValues;
        });

    return this;
  }

  @Override
  public TypeValueBuilderImpl putField(String name, TypedValue... values) {
    if (values.length > 0) {
      putArrayField(name, values);
    }
    return this;
  }

  @Override
  public TypeValueBuilderImpl putField(String name, TypedValue value) {
    TypedField field = fieldMap.get(name);
    if (field != null) {
      Type type = field.getType();
      if (type.isSimple()) {
        value = wrapSimpleValue(type, value);
      }
      if (field.getType().canAccept(value)) {
        fieldValueMap.put(name, new TypedFieldValue(field, value));
      } else {
        throw new IllegalArgumentException();
      }
    }
    return this;
  }

  private TypedValue wrapSimpleValue(Type targetType, TypedValue value) {
    TypedField valueField = targetType.getFields().get(0);
    Type fieldType = valueField.getType();
    if (fieldType.canAccept(value)) {
      value =
          TypedValue.of(
              targetType,
              Collections.singletonMap(
                  valueField.getName(), new TypedFieldValue(valueField, value)));
    } else {
      throw new IllegalArgumentException();
    }
    return value;
  }

  @Override
  public TypeValueBuilderImpl putField(String name, Consumer<TypeValueBuilder> fieldValueCallback) {
    TypedField field = fieldMap.get(name);
    if (field != null) {
      fieldValueMap.put(
          name, new TypedFieldValue(field, field.getType().asValue(fieldValueCallback)));
    }
    return this;
  }

  @Override
  public TypeValueBuilderImpl putField(
      String name, Consumer<TypeValueBuilder>... fieldValueCallbacks) {
    if (fieldValueCallbacks.length > 0) {
      buildArrayField(name, () -> fieldValueCallbacks);
    }
    return this;
  }

  Map<String, TypedFieldValue> build() {
    return Collections.unmodifiableMap(fieldValueMap);
  }

  private void putArrayField(String name, TypedValue[] values) {
    TypedField field = fieldMap.get(name);
    if (field != null) {
      putArrayField(field, values);
    } else {
      throw new IllegalArgumentException();
    }
  }

  private void putArrayField(TypedField field, TypedValue[] values) {
    Type fieldType = field.getType();
    for (TypedValue value : values) {
      if (!fieldType.canAccept(value)) {
        throw new IllegalArgumentException();
      }
    }
    fieldValueMap.put(field.getName(), new TypedFieldValue(field, values));
  }

  private void putArrayField(String name, Supplier<TypedValue[]> valueSupplier) {
    TypedField field = fieldMap.get(name);
    if (field != null) {
      if (!field.isArray()) {
        throw new IllegalArgumentException();
      }
      putArrayField(field, valueSupplier.get());
    } else {
      throw new IllegalArgumentException();
    }
  }

  private void buildArrayField(
      String name, Supplier<Consumer<TypeValueBuilder>[]> builderSupplier) {
    TypedField field = fieldMap.get(name);
    if (field != null) {
      if (field.isArray()) {
        Consumer<TypeValueBuilder>[] builders = builderSupplier.get();
        TypedValue[] values = new TypedValue[builders.length];
        Type fieldType = field.getType();
        for (int i = 0; i < builders.length; i++) {
          values[i] = fieldType.asValue(builders[i]);
        }
        fieldValueMap.put(field.getName(), new TypedFieldValue(field, values));
      } else {
        throw new IllegalArgumentException();
      }
    } else {
      throw new IllegalArgumentException();
    }
  }
}
