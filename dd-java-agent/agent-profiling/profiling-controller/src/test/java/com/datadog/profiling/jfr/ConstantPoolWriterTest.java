package com.datadog.profiling.jfr;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ConstantPoolWriterTest {
  private ConstantPools constantPools;
  private Types types;
  private ByteArrayWriter writer;
  private Type customType;
  private ConstantPool instance;

  @BeforeEach
  void setup() {
    constantPools = new ConstantPools();
    Metadata metadata = new Metadata(constantPools);
    types = new Types(metadata);

    writer = new ByteArrayWriter(4096);

    customType =
        types.getOrAdd(
            "custom.Type",
            t -> {
              t.addField("field", Types.Builtin.STRING);
            });

    instance = new ConstantPool(customType);
  }

  @Test
  void writeNull() {
    for (boolean useCpFlag : new boolean[] {true, false}) {
      assertThrows(
          NullPointerException.class, () -> instance.writeValueType(writer, null, useCpFlag));
      assertThrows(
          NullPointerException.class, () -> instance.writeBuiltinType(writer, null, useCpFlag));
    }
  }

  @Test
  void writeNullValue() {
    for (boolean useCpFlag : new boolean[] {true, false}) {
      instance.writeValueType(writer, customType.nullValue(), useCpFlag);
      for (Types.Builtin builtin : Types.Builtin.values()) {
        instance.writeBuiltinType(writer, types.getType(builtin).nullValue(), useCpFlag);
      }
    }
  }

  @Test
  void writeCustomAsBuiltin() {
    TypedValue value =
        customType.asValue(
            v -> {
              v.putField("field", "value");
            });

    for (boolean useCpFlag : new boolean[] {true, false}) {
      assertThrows(
          IllegalArgumentException.class,
          () -> instance.writeBuiltinType(writer, value, useCpFlag));
    }
  }

  @Test
  void writeString() {
    Type type = types.getType(Types.Builtin.STRING);
    for (boolean useCpFlag : new boolean[] {true, false}) {
      for (String strVal : new String[] {null, "", "value"}) {
        instance.writeBuiltinType(writer, type.asValue(strVal), useCpFlag);
      }
    }
  }
}
