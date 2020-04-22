package com.datadog.profiling.jfr;

public final class JfrChunkWriter {
  private static final byte[] MAGIC = new byte[] {'F', 'L', 'R', '\0'};
  private static final short MAJOR_VERSION = 2;
  private static final short MINOR_VERSION = 0;

  private static final long CHUNK_SIZE_OFFSET = 8;
  private static final long CONSTANT_OFFSET_OFFSET = 16;
  private static final long METADATA_OFFSET_OFFSET = 24;
  private static final long DURATION_NANOS_OFFSET = 40;

  private final ByteArrayWriter writer = new ByteArrayWriter(65536);
  private final Types types;
  private final long ts;

  JfrChunkWriter(Types types) {
    this.types = types;
    this.ts = System.nanoTime();
    writeHeader();
  }

  private void writeCheckPoint() {
    writer.writeLongRaw(CONSTANT_OFFSET_OFFSET, writer.length());

    ByteArrayWriter cpWriter = new ByteArrayWriter(4096);

    cpWriter
        .writeLong(1L) // checkpoint event ID
        .writeLong(ts) // start timestamp
        .writeLong(System.nanoTime() - ts) // duration till now
        .writeLong(0L) // fake delta-to-next
        .writeInt(1) // all checkpoints are flush for now
        .writeInt(types.getConstantPools().size()); // start writing constant pools array

    for (ConstantPool cp : types.getConstantPools()) {
      cp.writeCheckpoint(cpWriter);
    }

    int len = cpWriter.length();
    int extraLen = 0;
    do {
      extraLen = ByteArrayWriter.getPackedIntLen(len + extraLen);
    } while (ByteArrayWriter.getPackedIntLen(len + extraLen) != extraLen);

    writer
        .writeInt(len + extraLen) // write event size
        .writeBytes(cpWriter.toByteArray());
  }

  private void writeHeader() {
    writer
        .writeBytes(MAGIC)
        .writeShortRaw(MAJOR_VERSION)
        .writeShortRaw(MINOR_VERSION)
        .writeLongRaw(0L) // size
        .writeLongRaw(0L) // CP event offset
        .writeLongRaw(0L) // meta event offset
        .writeLongRaw(ts)
        .writeLongRaw(0L)
        .writeLongRaw(ts)
        .writeLongRaw(1_000_000_000L) // 1 tick = 1 ns
        .writeIntRaw(1); // use compressed integers
  }

  private void writeMetadata(long duration) {
    writer.writeLongRaw(METADATA_OFFSET_OFFSET, writer.length());

    types.getMetadata().writeMetaEvent(writer, ts, duration);
  }

  public JfrChunkWriter writeEvent(TypedValue event) {
    if (!event.getType().getSupertype().equals("jdk.jfr.Event")) {
      throw new IllegalArgumentException();
    }

    ByteArrayWriter eventWriter = new ByteArrayWriter(32767);
    eventWriter.writeLong(event.getType().getId());
    for (TypedFieldValue fieldValue : event.getFieldValues()) {
      writeTypedValue(eventWriter, fieldValue.getValue());
    }

    int len = eventWriter.length();
    int extraLen = 0;
    do {
      extraLen = ByteArrayWriter.getPackedIntLen(len + extraLen);
    } while (ByteArrayWriter.getPackedIntLen(len + extraLen) != extraLen);
    writer
        .writeInt(len + extraLen) // write event size
        .writeBytes(eventWriter.toByteArray());
    return this;
  }

  private void writeTypedValue(ByteArrayWriter writer, TypedValue value) {
    if (value == null) {
      throw new IllegalArgumentException();
    }

    Type t = value.getType();
    if (t.isBuiltin()) {
      writeBuiltinType(writer, value);
    } else {
      if (value.getType().hasConstantPool()) {
        writer.writeLong(value.getCPIndex());
      } else {
        for (TypedFieldValue fieldValue : value.getFieldValues()) {
          if (fieldValue.isArray()) {
            writer.writeInt(fieldValue.getValues().length); // array size
            for (TypedValue tValue : fieldValue.getValues()) {
              writeTypedValue(writer, tValue);
            }
          } else {
            writeTypedValue(writer, fieldValue.getValue());
          }
        }
      }
    }
  }

  private void writeBuiltinType(ByteArrayWriter writer, TypedValue typedValue) {
    if (typedValue == null) {
      throw new RuntimeException();
    }

    if (!typedValue.isBuiltin()) {
      throw new IllegalArgumentException();
    }

    Type type = typedValue.getType();
    Object value = typedValue.getValue();
    Types.Builtin builtin = Types.Builtin.ofType(type);
    if (value == null && builtin != Types.Builtin.STRING) {
      // skip the non-string built-in values
      return;
    }
    switch (builtin) {
      case STRING:
        {
          if (value == null) {
            writer.writeByte((byte) 0);
          } else if (((String) value).isEmpty()) {
            writer.writeByte((byte) 1);
          } else {
            long idx = typedValue.getCPIndex();
            if (idx > Long.MIN_VALUE) {
              writer.writeByte((byte) 2).writeLong(idx);
            } else {
              writer.writeUTF((String) value);
            }
          }
          break;
        }
      case BYTE:
        {
          writer.writeByte((byte) value);
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

  public byte[] dump() {
    long duration = System.nanoTime() - ts;
    writeCheckPoint();
    writeMetadata(duration);
    writer.writeLongRaw(DURATION_NANOS_OFFSET, duration);
    writer.writeLongRaw(CHUNK_SIZE_OFFSET, (long) writer.length());
    return writer.toByteArray();
  }
}
