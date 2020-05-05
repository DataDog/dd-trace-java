package com.datadog.profiling.jfr;

import java.util.HashMap;
import java.util.Map;

public class TypeUtils {
  public static Map<Types.Builtin, Object> BUILTIN_VALUE_MAP;

  static {
    BUILTIN_VALUE_MAP = new HashMap<>();
    BUILTIN_VALUE_MAP.put(Types.Builtin.BOOLEAN, true);
    BUILTIN_VALUE_MAP.put(Types.Builtin.BYTE, (byte) 0x12);
    BUILTIN_VALUE_MAP.put(Types.Builtin.CHAR, 'h');
    BUILTIN_VALUE_MAP.put(Types.Builtin.SHORT, (short) 4);
    BUILTIN_VALUE_MAP.put(Types.Builtin.INT, 7);
    BUILTIN_VALUE_MAP.put(Types.Builtin.LONG, 1256L);
    BUILTIN_VALUE_MAP.put(Types.Builtin.FLOAT, 3.14f);
    BUILTIN_VALUE_MAP.put(Types.Builtin.DOUBLE, Math.sqrt(2d));
    BUILTIN_VALUE_MAP.put(Types.Builtin.STRING, "hello");
  }
}
