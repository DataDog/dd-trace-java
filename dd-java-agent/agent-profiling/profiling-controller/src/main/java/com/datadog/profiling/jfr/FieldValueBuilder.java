package com.datadog.profiling.jfr;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** A fluent API for lazy initialization of a custom type value */
public final class FieldValueBuilder {
  private final Metadata metadata;
  private final Map<String, TypedField> fieldMap;
  private final Map<String, TypedFieldValue> fieldValueMap;

  FieldValueBuilder(JFRType type) {
    this.metadata = type.getMetadata();
    fieldMap =
        type.getFields()
            .stream()
            .collect(Collectors.toMap(TypedField::getName, typeField -> typeField));
    fieldValueMap = new HashMap<>();
  }

  public FieldValueBuilder putField(String name, byte value) {
    return putField(name, TypedValue.of(metadata.getType(Types.Builtin.BYTE, false), value));
  }

  public FieldValueBuilder putField(String name, byte[] values) {
    putArrayField(
        name,
        () -> {
          JFRType type = metadata.getType(Types.Builtin.BYTE, false);
          TypedValue[] typedValues = new TypedValue[values.length];
          for (int i = 0; i < values.length; i++) {
            typedValues[i] = TypedValue.of(type, values[i]);
          }
          return typedValues;
        });

    return this;
  }

  public FieldValueBuilder putField(String name, char value) {
    return putField(name, TypedValue.of(metadata.getType(Types.Builtin.CHAR, false), value));
  }

  public FieldValueBuilder putField(String name, char[] values) {
    putArrayField(
        name,
        () -> {
          JFRType type = metadata.getType(Types.Builtin.CHAR, false);
          TypedValue[] typedValues = new TypedValue[values.length];
          for (int i = 0; i < values.length; i++) {
            typedValues[i] = TypedValue.of(type, values[i]);
          }
          return typedValues;
        });

    return this;
  }

  public FieldValueBuilder putField(String name, short value) {
    return putField(name, TypedValue.of(metadata.getType(Types.Builtin.SHORT, false), value));
  }

  public FieldValueBuilder putField(String name, short[] values) {
    putArrayField(
        name,
        () -> {
          JFRType type = metadata.getType(Types.Builtin.SHORT, false);
          TypedValue[] typedValues = new TypedValue[values.length];
          for (int i = 0; i < values.length; i++) {
            typedValues[i] = TypedValue.of(type, values[i]);
          }
          return typedValues;
        });

    return this;
  }

  public FieldValueBuilder putField(String name, int value) {
    return putField(name, TypedValue.of(metadata.getType(Types.Builtin.INT, false), value));
  }

  public FieldValueBuilder putField(String name, int[] values) {
    putArrayField(
        name,
        () -> {
          JFRType type = metadata.getType(Types.Builtin.INT, false);
          TypedValue[] typedValues = new TypedValue[values.length];
          for (int i = 0; i < values.length; i++) {
            typedValues[i] = TypedValue.of(type, values[i]);
          }
          return typedValues;
        });

    return this;
  }

  public FieldValueBuilder putField(String name, long value) {
    return putField(name, TypedValue.of(metadata.getType(Types.Builtin.LONG, false), value));
  }

  public FieldValueBuilder putField(String name, long[] values) {
    putArrayField(
        name,
        () -> {
          JFRType type = metadata.getType(Types.Builtin.LONG, false);
          TypedValue[] typedValues = new TypedValue[values.length];
          for (int i = 0; i < values.length; i++) {
            typedValues[i] = TypedValue.of(type, values[i]);
          }
          return typedValues;
        });

    return this;
  }

  public FieldValueBuilder putField(String name, float value) {
    return putField(name, TypedValue.of(metadata.getType(Types.Builtin.FLOAT, false), value));
  }

  public FieldValueBuilder putField(String name, float[] values) {
    putArrayField(
        name,
        () -> {
          JFRType type = metadata.getType(Types.Builtin.FLOAT, false);
          TypedValue[] typedValues = new TypedValue[values.length];
          for (int i = 0; i < values.length; i++) {
            typedValues[i] = TypedValue.of(type, values[i]);
          }
          return typedValues;
        });

    return this;
  }

  public FieldValueBuilder putField(String name, double value) {
    return putField(name, TypedValue.of(metadata.getType(Types.Builtin.DOUBLE, false), value));
  }

  public FieldValueBuilder putField(String name, double[] values) {
    putArrayField(
        name,
        () -> {
          JFRType type = metadata.getType(Types.Builtin.DOUBLE, false);
          TypedValue[] typedValues = new TypedValue[values.length];
          for (int i = 0; i < values.length; i++) {
            typedValues[i] = TypedValue.of(type, values[i]);
          }
          return typedValues;
        });

    return this;
  }

  public FieldValueBuilder putField(String name, boolean value) {
    return putField(name, TypedValue.of(metadata.getType(Types.Builtin.BOOLEAN, false), value));
  }

  public FieldValueBuilder putField(String name, boolean[] values) {
    putArrayField(
        name,
        () -> {
          JFRType type = metadata.getType(Types.Builtin.BOOLEAN, false);
          TypedValue[] typedValues = new TypedValue[values.length];
          for (int i = 0; i < values.length; i++) {
            typedValues[i] = TypedValue.of(type, values[i]);
          }
          return typedValues;
        });

    return this;
  }

  public FieldValueBuilder putField(String name, String value) {
    return putField(name, TypedValue.of(metadata.getType(Types.Builtin.STRING, false), value));
  }

  public FieldValueBuilder putField(String name, String[] values) {
    putArrayField(
        name,
        () -> {
          JFRType type = metadata.getType(Types.Builtin.STRING, false);
          TypedValue[] typedValues = new TypedValue[values.length];
          for (int i = 0; i < values.length; i++) {
            typedValues[i] = TypedValue.of(type, values[i]);
          }
          return typedValues;
        });

    return this;
  }

  public FieldValueBuilder putField(String name, TypedValue... values) {
    if (values.length > 0) {
      putArrayField(name, values);
    }
    return this;
  }

  public FieldValueBuilder putField(String name, TypedValue value) {
    TypedField field = fieldMap.get(name);
    if (field != null) {
      JFRType type = field.getType();
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

  private TypedValue wrapSimpleValue(JFRType targetType, TypedValue value) {
    TypedField valueField = targetType.getFields().get(0);
    JFRType fieldType = valueField.getType();
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

  public FieldValueBuilder putField(String name, Consumer<FieldValueBuilder> fieldValueBuilder) {
    TypedField field = fieldMap.get(name);
    if (field != null) {
      fieldValueMap.put(
          name, new TypedFieldValue(field, field.getType().asValue(fieldValueBuilder)));
    }
    return this;
  }

  public FieldValueBuilder putField(String name, Consumer<FieldValueBuilder>... builders) {
    if (builders.length > 0) {
      buildArrayField(name, () -> builders);
    }
    return this;
  }

  Map<String, TypedFieldValue> getFieldValues() {
    return Collections.unmodifiableMap(fieldValueMap);
  }

  private void putArrayField(String name, TypedValue[] values) {
    TypedField field = fieldMap.get(name);
    if (field != null) {
      putArrayField(field, values);
    }
  }

  private void putArrayField(TypedField field, TypedValue[] values) {
    JFRType fieldType = field.getType();
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
    }
  }

  private void buildArrayField(
      String name, Supplier<Consumer<FieldValueBuilder>[]> builderSupplier) {
    TypedField field = fieldMap.get(name);
    if (field != null) {
      Consumer<FieldValueBuilder>[] builders = builderSupplier.get();
      TypedValue[] values = new TypedValue[builders.length];
      JFRType fieldType = field.getType();
      for (int i = 0; i < builders.length; i++) {
        values[i] = fieldType.asValue(builders[i]);
      }
      fieldValueMap.put(field.getName(), new TypedFieldValue(field, values));
    }
  }
}
