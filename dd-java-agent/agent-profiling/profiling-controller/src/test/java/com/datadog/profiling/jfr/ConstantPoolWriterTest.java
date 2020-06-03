package com.datadog.profiling.jfr;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.datadog.profiling.util.LEB128ByteArrayWriter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConstantPoolWriterTest {
  private ConstantPools constantPools;
  private Types types;
  private LEB128ByteArrayWriter writer;
  private Type customType;
  private ConstantPool instance;

  @BeforeEach
  void setup() {
    constantPools = new ConstantPools();
    Metadata metadata = new Metadata(constantPools);
    types = new Types(metadata);

    writer = new LEB128ByteArrayWriter(4096);

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
  void writeBuiltinByte() {
    for (boolean useCpFlag : new boolean[] {true, false}) {
      instance.writeBuiltinType(
          writer, types.getType(Types.Builtin.BYTE).asValue((byte) 1), useCpFlag);
    }
  }

  @Test
  void writeBuiltinChar() {
    for (boolean useCpFlag : new boolean[] {true, false}) {
      instance.writeBuiltinType(
          writer, types.getType(Types.Builtin.CHAR).asValue((char) 1), useCpFlag);
    }
  }

  @Test
  void writeBuiltinShort() {
    for (boolean useCpFlag : new boolean[] {true, false}) {
      instance.writeBuiltinType(
          writer, types.getType(Types.Builtin.SHORT).asValue((short) 1), useCpFlag);
    }
  }

  @Test
  void writeBuiltinInt() {
    for (boolean useCpFlag : new boolean[] {true, false}) {
      instance.writeBuiltinType(
          writer, types.getType(Types.Builtin.INT).asValue((int) 1), useCpFlag);
    }
  }

  @Test
  void writeBuiltinLong() {
    for (boolean useCpFlag : new boolean[] {true, false}) {
      instance.writeBuiltinType(
          writer, types.getType(Types.Builtin.LONG).asValue((long) 1), useCpFlag);
    }
  }

  @Test
  void writeBuiltinFloat() {
    for (boolean useCpFlag : new boolean[] {true, false}) {
      instance.writeBuiltinType(
          writer, types.getType(Types.Builtin.FLOAT).asValue((float) 1), useCpFlag);
    }
  }

  @Test
  void writeBuiltinDouble() {
    for (boolean useCpFlag : new boolean[] {true, false}) {
      instance.writeBuiltinType(
          writer, types.getType(Types.Builtin.DOUBLE).asValue((double) 1), useCpFlag);
    }
  }

  @Test
  void writeBuiltinBoolean() {
    for (boolean useCpFlag : new boolean[] {true, false}) {
      instance.writeBuiltinType(
          writer, types.getType(Types.Builtin.BOOLEAN).asValue(true), useCpFlag);
    }
  }

  @Test
  void writeBuiltinString() {
    for (boolean useCpFlag : new boolean[] {true, false}) {
      instance.writeBuiltinType(
          writer, types.getType(Types.Builtin.STRING).asValue("1"), useCpFlag);
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
