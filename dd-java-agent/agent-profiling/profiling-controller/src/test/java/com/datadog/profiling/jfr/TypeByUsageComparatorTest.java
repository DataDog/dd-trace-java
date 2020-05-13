package com.datadog.profiling.jfr;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TypeByUsageComparatorTest {
  private Types types;
  private TypeByUsageComparator instance;

  @BeforeEach
  void setup() {
    ConstantPools constantPools = new ConstantPools();
    Metadata metadata = new Metadata(constantPools);
    types = new Types(metadata);

    instance = new TypeByUsageComparator();
  }

  @Test
  void compare() {
    Type type1 = types.getType(Types.Builtin.STRING);
    Type type2 = types.getType(Types.JDK.CLASS);

    assertEquals(0, instance.compare(type1, type1));
    assertEquals(0, instance.compare(type2, type2));
    assertEquals(0, instance.compare(null, null));
    assertEquals(1, instance.compare(type1, null));
    assertEquals(-1, instance.compare(null, type1));
    assertEquals(-1, instance.compare(type1, type2));
    assertEquals(1, instance.compare(type2, type1));
  }
}
