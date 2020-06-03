package com.datadog.profiling.jfr;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.datadog.profiling.util.LEB128ByteArrayWriter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

class ChunkTest {
  private Chunk instance;
  private Types types;
  private ConstantPools constantPools;
  private Metadata metadata;
  private LEB128ByteArrayWriter writer;

  @BeforeEach
  void setup() {
    writer = new LEB128ByteArrayWriter(4096);
    constantPools = new ConstantPools();
    metadata = new Metadata(constantPools);
    types = new Types(metadata);
    instance = new Chunk(metadata, constantPools);
  }

  @Test
  void writeEventWrongType() {
    assertThrows(
        IllegalArgumentException.class,
        () -> instance.writeEvent(types.getType(Types.Builtin.STRING).asValue("value")));
  }

  @Test
  void writeNullValue() {
    assertThrows(IllegalArgumentException.class, () -> instance.writeTypedValue(writer, null));
  }

  @ParameterizedTest
  @EnumSource(Types.Builtin.class)
  void writeBuiltinValue(Types.Builtin target) {
    for (Object value : TypeUtils.getBuiltinValues(target)) {
      Type type = types.getType(target);
      TypedValue tv = new TypedValue(type, value, 1);
      instance.writeTypedValue(writer, tv);
      instance.writeTypedValue(writer, type.nullValue());
    }
  }

  @Test
  void writeUnknownBuiltin() {
    Type type =
        Mockito.mock(
            BaseType.class,
            Mockito.withSettings().useConstructor(1L, "unknown.Builtin", null, metadata));
    Mockito.when(type.isBuiltin()).thenReturn(true);
    Mockito.when(type.canAccept(ArgumentMatchers.any())).thenReturn(true);

    assertThrows(
        IllegalArgumentException.class,
        () -> instance.writeTypedValue(writer, new TypedValue(type, "hello", 10)));
  }

  @Test
  void writeCustomNoCP() {
    Type stringType = types.getType(Types.Builtin.STRING);
    List<TypedField> fields =
        Arrays.asList(
            new TypedField(stringType, "string", false),
            new TypedField(stringType, "strings", true));
    TypeStructure structure = new TypeStructure(fields, Collections.emptyList());

    Type type = new CompositeType(1000, "custom.Type", null, structure, null, metadata);

    instance.writeTypedValue(
        writer,
        TypedValue.of(
            type,
            v -> {
              v.putField("string", "value1").putField("strings", new String[] {"value2", "value4"});
            }));
  }
}
