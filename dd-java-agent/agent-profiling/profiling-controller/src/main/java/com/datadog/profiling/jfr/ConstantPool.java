package com.datadog.profiling.jfr;

import com.datadog.profiling.util.LEB128ByteArrayWriter;
import java.util.HashMap;
import java.util.Map;

/** An in-memory map of distinct values of a certain {@linkplain Type} */
final class ConstantPool {
  private final Type type;
  private final Map<Object, TypedValue> constantMap = new HashMap<>();
  private final Map<Long, TypedValue> reverseMap = new HashMap<>();

  ConstantPool(Type type) {
    this.type = type;
  }

  /**
   * Tries to add a new value
   *
   * @param value the value
   * @return the typed value representation - either created a-new or retrieved from the pool
   */
  TypedValue addOrGet(Object value) {
    if (value == null) {
      return type.nullValue();
    }
    return constantMap.computeIfAbsent(
        value,
        v -> {
          long index = constantMap.size() + 1; // index 0 is reserved for NULL encoding
          TypedValue tValue;
          if (v instanceof TypedValue) {
            tValue = TypedValue.withCpIndex((TypedValue) v, index);
          } else {
            tValue = new TypedValue(type, v, index);
          }
          reverseMap.put(index, tValue);
          return tValue;
        });
  }

  /**
   * Get the value at the given index
   *
   * @param index the value index
   * @return the value or {@literal null}
   */
  TypedValue get(long index) {
    return reverseMap.get(index);
  }

  void writeTo(LEB128ByteArrayWriter writer) {
    writer.writeLong(type.getId()); // CP type ID
    writer.writeInt(constantMap.size()); // number of constants
    reverseMap.forEach(
        (k, v) -> {
          writer.writeLong(k); // constant index
          writeValueType(writer, v, false);
        });
  }

  void writeValueType(LEB128ByteArrayWriter writer, TypedValue typedValue, boolean useCp) {
    if (typedValue == null) {
      throw new NullPointerException();
    }
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
            if (fieldValue.getField().isArray()) {
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

  void writeBuiltinType(LEB128ByteArrayWriter writer, TypedValue typedValue, boolean useCp) {
    if (typedValue == null) {
      throw new NullPointerException();
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
            writer.writeCompactUTF((String) value);
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
