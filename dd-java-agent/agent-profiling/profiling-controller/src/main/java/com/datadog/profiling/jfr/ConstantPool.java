package com.datadog.profiling.jfr;

import java.util.HashMap;
import java.util.Map;

final class ConstantPool {
  private final Type type;
  private final Map<Object, TypedValue> constantMap = new HashMap<>();
  private final Map<Long, TypedValue> reverseMap = new HashMap<>();

  protected ConstantPool(Type type) {
    this.type = type;
  }

  final TypedValue addOrGet(Object value) {
    if (value == null) {
      return type.nullValue();
    }
    return constantMap.computeIfAbsent(
        value,
        v -> {
          long index = constantMap.size() + 1;
          TypedValue tValue;
          if (v instanceof TypedValue) {
            tValue = TypedValue.cpClone((TypedValue) v, index);
          } else {
            tValue = new TypedValue(type, v, index);
          }
          reverseMap.put(index, tValue);
          return tValue;
        });
  }

  TypedValue get(long index) {
    return reverseMap.get(index);
  }

  final void writeCheckpoint(ByteArrayWriter writer) {
    writer.writeLong(type.getId()); // CP type ID
    writer.writeInt(constantMap.size()); // number of constants
    for (Map.Entry<Long, TypedValue> entry : reverseMap.entrySet()) {
      writer.writeLong(entry.getKey()); // constant index
      writeValueType(writer, entry.getValue(), false);
    }
  }

  private void writeValueType(ByteArrayWriter writer, TypedValue typedValue, boolean useCp) {
    Type type = typedValue.getType();
    if (type.isBuiltin()) {
      writeBuiltinType(writer, typedValue, useCp);
    } else {
      if (typedValue.isNull()) {
        writer.writeLong(0); // null value encoding
      } else {
        if (useCp) { // (assumption) all custom types have constant pool
          writer.writeLong(typedValue.getCPIndex());
        } else {
          for (TypedFieldValue fieldValue : typedValue.getFieldValues()) {
            if (fieldValue == null) {
              throw new RuntimeException();
            }
            if (fieldValue.isArray()) {
              writer.writeInt(fieldValue.getValues().length); // array length
              for (TypedValue t : fieldValue.getValues()) {
                writeValueType(writer, t, true);
              }
            } else {
              writeValueType(writer, fieldValue.getValue(), true);
            }
          }
        }
      }
    }
  }

  private void writeBuiltinType(ByteArrayWriter writer, TypedValue typedValue, boolean useCp) {
    if (typedValue == null) {
      throw new RuntimeException();
    }

    if (!typedValue.getType().isBuiltin()) {
      throw new IllegalArgumentException();
    }

    Type type = typedValue.getType();
    Object value = typedValue.getValue();
    Types.Builtin builtin = Types.Builtin.ofType(type);
    if (value == null && builtin != Types.Builtin.STRING) {
      // skip the non-string built-in values but use CP index '0' if CP is requested
      if (useCp) {
        writer.writeLong(0L);
      }
      return;
    }
    switch (builtin) {
      case STRING:
        {
          if (useCp) {
            if (typedValue.isNull()) {
              writer.writeByte((byte) 0); // skip CP for NULL
            } else if (((String) typedValue.getValue()).isEmpty()) {
              writer.writeByte((byte) 1); // skip CP for empty string
            } else {
              writer
                  .writeByte((byte) 2) // set constant-pool encoding
                  .writeLong(typedValue.getCPIndex());
            }
          } else {
            writer.writeUTF((String) value);
          }
          break;
        }
      case BYTE:
        {
          writer.writeBytes((byte) value);
          break;
        }
      case CHAR:
        {
          writer.writeChar((char) value);
          break;
        }
      case SHORT:
        {
          writer.writeShort((short) value);
          break;
        }
      case INT:
        {
          writer.writeInt((int) value);
          break;
        }
      case LONG:
        {
          writer.writeLong((long) value);
          break;
        }
      case FLOAT:
        {
          writer.writeFloat((float) value);
          break;
        }
      case DOUBLE:
        {
          writer.writeDouble((double) value);
          break;
        }
      case BOOLEAN:
        {
          writer.writeBoolean((boolean) value);
          break;
        }
      default:
        {
          throw new IllegalArgumentException("Unsupported built-in type " + type.getTypeName());
        }
    }
  }
}
