package com.datadog.debugger.instrumentation;

import static org.objectweb.asm.Opcodes.AALOAD;
import static org.objectweb.asm.Opcodes.AASTORE;
import static org.objectweb.asm.Opcodes.BALOAD;
import static org.objectweb.asm.Opcodes.BASTORE;
import static org.objectweb.asm.Opcodes.CALOAD;
import static org.objectweb.asm.Opcodes.CASTORE;
import static org.objectweb.asm.Opcodes.DALOAD;
import static org.objectweb.asm.Opcodes.DASTORE;
import static org.objectweb.asm.Opcodes.FALOAD;
import static org.objectweb.asm.Opcodes.FASTORE;
import static org.objectweb.asm.Opcodes.IALOAD;
import static org.objectweb.asm.Opcodes.IASTORE;
import static org.objectweb.asm.Opcodes.LALOAD;
import static org.objectweb.asm.Opcodes.LASTORE;
import static org.objectweb.asm.Opcodes.SALOAD;
import static org.objectweb.asm.Opcodes.SASTORE;

import datadog.trace.bootstrap.debugger.CorrelationAccess;
import datadog.trace.bootstrap.debugger.DebuggerContext;
import datadog.trace.bootstrap.debugger.Snapshot;
import datadog.trace.bootstrap.debugger.SnapshotProvider;
import java.util.HashMap;
import java.util.Map;
import java.util.StringTokenizer;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/** ASM type constants used by code generating instrumentation */
public final class Types {
  private static final Map<String, String> PRIMITIVE_TYPES = new HashMap<>();
  private static final Map<Type, Type> BOXING_TARGET_TYPES = new HashMap<>();

  // common Type constants
  public static final Type STRING_TYPE = Type.getType(String.class);
  public static final Type OBJECT_ARRAY_TYPE = Type.getType(Object[].class);
  public static final Type OBJECT_TYPE = Type.getType(Object.class);
  public static final Type MAP_TYPE = Type.getType(Map.class);
  public static final Type HASHMAP_TYPE = Type.getType(HashMap.class);
  public static final Type SNAPSHOT_TYPE = Type.getType(Snapshot.class);
  public static final Type SNAPSHOTPROVIDER_TYPE = Type.getType(SnapshotProvider.class);
  public static final Type CAPTURED_VALUE = Type.getType(Snapshot.CapturedValue.class);
  public static final Type CAPTURE_CONTEXT_TYPE = Type.getType(Snapshot.CapturedContext.class);
  public static final Type CAPTURE_THROWABLE_TYPE = Type.getType(Snapshot.CapturedThrowable.class);
  public static final Type THROWABLE_TYPE = Type.getType(Throwable.class);
  public static final Type CORRELATION_ACCESS_TYPE = Type.getType(CorrelationAccess.class);
  public static final Type DEBUGGER_CONTEXT_TYPE = Type.getType(DebuggerContext.class);
  public static final Type CLASS_TYPE = Type.getType(Class.class);

  // special initialization methods
  public static final String CONSTRUCTOR = "<init>";
  public static final String CLASS_INIT = "<clinit>";

  static {
    PRIMITIVE_TYPES.put("byte", Type.BYTE_TYPE.getInternalName());
    PRIMITIVE_TYPES.put("char", Type.CHAR_TYPE.getInternalName());
    PRIMITIVE_TYPES.put("short", Type.SHORT_TYPE.getInternalName());
    PRIMITIVE_TYPES.put("int", Type.INT_TYPE.getInternalName());
    PRIMITIVE_TYPES.put("long", Type.LONG_TYPE.getInternalName());
    PRIMITIVE_TYPES.put("float", Type.FLOAT_TYPE.getInternalName());
    PRIMITIVE_TYPES.put("double", Type.DOUBLE_TYPE.getInternalName());
    PRIMITIVE_TYPES.put("boolean", Type.BOOLEAN_TYPE.getInternalName());
    PRIMITIVE_TYPES.put("void", Type.VOID_TYPE.getInternalName());

    BOXING_TARGET_TYPES.put(Type.BYTE_TYPE, Type.getType(Byte.class));
    BOXING_TARGET_TYPES.put(Type.CHAR_TYPE, Type.getType(Character.class));
    BOXING_TARGET_TYPES.put(Type.SHORT_TYPE, Type.getType(Short.class));
    BOXING_TARGET_TYPES.put(Type.INT_TYPE, Type.getType(Integer.class));
    BOXING_TARGET_TYPES.put(Type.LONG_TYPE, Type.getType(Long.class));
    BOXING_TARGET_TYPES.put(Type.FLOAT_TYPE, Type.getType(Float.class));
    BOXING_TARGET_TYPES.put(Type.DOUBLE_TYPE, Type.getType(Double.class));
    BOXING_TARGET_TYPES.put(Type.BOOLEAN_TYPE, Type.getType(Boolean.class));
  }

  public static boolean isPrimitive(Type type) {
    return type.getSort() != Type.ARRAY && type.getSort() != Type.OBJECT;
  }

  public static Type getBoxingTargetType(Type type) {
    return BOXING_TARGET_TYPES.get(type);
  }

  public static boolean isArray(Type type) {
    return type.getSort() == Type.ARRAY;
  }

  public static Type fromClassName(String className) {
    StringBuilder internal = new StringBuilder();
    int arrayMarker = className.indexOf("[]");
    if (arrayMarker == -1) {
      return Type.getType(getDescriptor(className));
    }
    if (arrayMarker == 0) {
      return null;
    }
    int dimensions = (className.length() - arrayMarker) / 2;
    for (int i = 0; i < dimensions; i++) {
      internal.append('[');
    }
    internal.append(getDescriptor(className.substring(0, arrayMarker)));
    return Type.getType(internal.toString());
  }

  /**
   * Turn the provided type into an array with the given number of dimensions
   *
   * @param element the array element type
   * @param dimensions the number of dimensions
   * @return a {@linkplain Type} instance representing an array of the given type
   */
  public static Type asArray(Type element, int dimensions) {
    if (isArray(element)) {
      return element;
    }
    if (dimensions == 0) {
      return element;
    }
    StringBuilder newDesc = new StringBuilder();
    for (int i = 0; i < dimensions; i++) {
      newDesc.append('[');
    }
    newDesc.append(element.getDescriptor());
    return Type.getType(newDesc.toString());
  }

  private static String getDescriptor(String className) {
    String t = PRIMITIVE_TYPES.get(className);
    if (t != null) {
      return t;
    }
    return "L" + className.replace('.', '/') + ";";
  }

  public static Type getArrayType(int arrayOpcode) {
    switch (arrayOpcode) {
      case IALOAD:
      case IASTORE:
        return Type.getType("[I");

      case BALOAD:
      case BASTORE:
        return Type.getType("[B");

      case AALOAD:
      case AASTORE:
        return OBJECT_ARRAY_TYPE;

      case CALOAD:
      case CASTORE:
        return Type.getType("[C");

      case FALOAD:
      case FASTORE:
        return Type.getType("[F");

      case SALOAD:
      case SASTORE:
        return Type.getType("[S");

      case LALOAD:
      case LASTORE:
        return Type.getType("[J");

      case DALOAD:
      case DASTORE:
        return Type.getType("[D");

      default:
        throw new RuntimeException("invalid array opcode");
    }
  }

  public static Type getElementType(int arrayOpcode) {
    switch (arrayOpcode) {
      case IALOAD:
      case IASTORE:
        return Type.INT_TYPE;

      case BALOAD:
      case BASTORE:
        return Type.BYTE_TYPE;

      case AALOAD:
      case AASTORE:
        return OBJECT_TYPE;

      case CALOAD:
      case CASTORE:
        return Type.CHAR_TYPE;

      case FALOAD:
      case FASTORE:
        return Type.FLOAT_TYPE;

      case SALOAD:
      case SASTORE:
        return Type.SHORT_TYPE;

      case LALOAD:
      case LASTORE:
        return Type.LONG_TYPE;

      case DALOAD:
      case DASTORE:
        return Type.DOUBLE_TYPE;

      default:
        throw new RuntimeException("invalid array opcode");
    }
  }

  public static Type getFrameItemType(Object frameItem) {
    if (frameItem instanceof Integer) {
      if (Opcodes.TOP.equals(frameItem)) {
        return null;
      }
      int frameItemKind = (int) frameItem;
      switch (frameItemKind) {
        case Opcodes.T_BYTE:
          {
            return Type.BYTE_TYPE;
          }
        case Opcodes.T_BOOLEAN:
          {
            return Type.BOOLEAN_TYPE;
          }
        case Opcodes.T_CHAR:
          {
            return Type.CHAR_TYPE;
          }
        case Opcodes.T_DOUBLE:
          {
            return Type.DOUBLE_TYPE;
          }
        case Opcodes.T_FLOAT:
          {
            return Type.FLOAT_TYPE;
          }
        case Opcodes.T_INT:
          {
            return Type.INT_TYPE;
          }
        case Opcodes.T_LONG:
          {
            return Type.LONG_TYPE;
          }
        case Opcodes.T_SHORT:
          {
            return Type.SHORT_TYPE;
          }
      }
    } else if (frameItem instanceof String) {
      return Type.getType((String) frameItem);
    }
    return null;
  }

  /**
   * Convert a Java-ish method signature in form of '&lt;return type&gt; (<&lt;rg_type_1&gt;, ...,
   * &lt;arg_type_n&gt;)' into the method descriptor recoginzed by JVM.
   *
   * @param javaSignature the java method signature
   * @return the JVM method descriptor
   */
  public static String descriptorFromSignature(String javaSignature) {
    int leftParen = javaSignature.indexOf('(');
    int rightParen = javaSignature.indexOf(')');
    if (leftParen == -1 || rightParen == -1) {
      throw new IllegalArgumentException(
          String.format(
              "Illegal java signature, missing matching parenthesis: '%s'. Must be of form '<return type> (<arg_type_1>, ..., <arg_type_n>)'",
              javaSignature));
    }

    StringBuilder buf = new StringBuilder();
    String descriptor;

    buf.append('(');
    String args = javaSignature.substring(leftParen + 1, rightParen).trim();
    StringTokenizer st = new StringTokenizer(args, ",");
    if (!st.hasMoreTokens() && !args.isEmpty()) {
      throw new IllegalArgumentException(
          String.format(
              "Illegal java signature, invalid argument format: '%s'. Must be of form '<return type> (<arg_type_1>, ..., <arg_type_n>)'",
              javaSignature));
    }
    while (st.hasMoreTokens()) {
      String arg = st.nextToken().trim();
      if (arg.length() == 0) {
        throw new IllegalArgumentException(
            String.format(
                "Illegal java signature, empty argument: '%s'. Must be of form '<return type> (<arg_type_1>, ..., <arg_type_n>)'",
                javaSignature));
      }
      descriptor = PRIMITIVE_TYPES.get(arg);
      if (descriptor == null) {
        descriptor = objectOrArrayDesc(arg);
      }
      buf.append(descriptor);
    }
    buf.append(')');

    String returnType = javaSignature.substring(0, leftParen).trim();
    if (returnType.length() == 0) {
      throw new IllegalArgumentException(
          String.format(
              "Illegal java signature: '%s'. Must be of form '<return type> (<arg_type_1>, ..., <arg_type_n>)'",
              javaSignature));
    }
    descriptor = PRIMITIVE_TYPES.get(returnType);
    if (descriptor == null) {
      descriptor = objectOrArrayDesc(returnType);
    }
    buf.append(descriptor);
    return buf.toString();
  }

  /**
   * Turn a type specified in Java syntax (eg. {@literal int}, {@literal java.lang.String} or
   * {@literal java.lang.String[]}) into the corresponding JVM type descriptor.
   *
   * @param javaType the type in Java syntax
   * @return the JVM type descriptor
   */
  public static String objectOrArrayDesc(String javaType) {
    StringBuilder buf = new StringBuilder();
    int index = 0;
    while ((index = javaType.indexOf("[]", index) + 1) > 0) {
      buf.append('[');
    }
    String t = javaType.substring(0, javaType.length() - buf.length() * 2);
    String desc = PRIMITIVE_TYPES.get(t);
    if (desc != null) {
      buf.append(desc);
    } else {
      buf.append('L');
      if (t.indexOf('.') < 0) {
        buf.append(t);
      } else {
        buf.append(t.replace('.', '/'));
      }
      buf.append(';');
    }
    return buf.toString();
  }
}
