package datadog.trace.plugin.csi.util;

import org.objectweb.asm.Type;

public abstract class Types {
  private Types() {}

  public static final Type BOOLEAN = Type.BOOLEAN_TYPE;
  public static final Type STRING = Type.getType(String.class);
  public static final Type OBJECT_ARRAY = Type.getType(Object[].class);
}
