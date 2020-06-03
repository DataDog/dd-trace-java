package com.datadog.profiling.jfr;

import com.datadog.profiling.util.LEB128ByteArrayWriter;

/** A representation of JFR chunk - self contained set of JFR data. */
public final class Chunk {
  private static final byte[] MAGIC = new byte[] {'F', 'L', 'R', '\0'};
  private static final short MAJOR_VERSION = 2;
  private static final short MINOR_VERSION = 0;

  private static final long CHUNK_SIZE_OFFSET = 8;
  private static final long CONSTANT_OFFSET_OFFSET = 16;
  private static final long METADATA_OFFSET_OFFSET = 24;
  private static final long DURATION_NANOS_OFFSET = 40;

  private final LEB128ByteArrayWriter writer = new LEB128ByteArrayWriter(65536);
  private final ConstantPools constantPools;
  private final Metadata metadata;
  private final long startTicks;
  private final long startNanos;

  Chunk(Metadata metadata, ConstantPools constantPools) {
    this.metadata = metadata;
    this.constantPools = constantPools;
    this.startTicks = System.nanoTime();
    this.startNanos = System.currentTimeMillis() * 1_000_000L;
    writeHeader();
  }

  /**
   * Write a custom event
   *
   * @param event the event value
   * @return {@literal this} for chaining
   * @throws IllegalArgumentException if the event type has not got 'jdk.jfr.Event' as its super
   *     type
   */
  public Chunk writeEvent(TypedValue event) {
    if (!"jdk.jfr.Event".equals(event.getType().getSupertype())) {
      throw new IllegalArgumentException();
    }

    LEB128ByteArrayWriter eventWriter = new LEB128ByteArrayWriter(32767);
    eventWriter.writeLong(event.getType().getId());
    for (TypedFieldValue fieldValue : event.getFieldValues()) {
      writeTypedValue(eventWriter, fieldValue.getValue());
    }

    writer
        .writeInt(eventWriter.length()) // write event size
        .writeBytes(eventWriter.toByteArray());
    return this;
  }

  /**
   * Finalize the chunk and return the byte array representation. The chunk should not be used after
   * it has been finished.
   *
   * @return the chunk raw data
   */
  public byte[] finish() {
    long duration = System.nanoTime() - startTicks;
    writeCheckPoint();
    writeMetadata(duration);
    writer.writeLongRaw(DURATION_NANOS_OFFSET, duration);
    writer.writeLongRaw(CHUNK_SIZE_OFFSET, writer.position());
    return writer.toByteArray();
  }

  private void writeCheckPoint() {
    writer.writeLongRaw(CONSTANT_OFFSET_OFFSET, writer.position());

    LEB128ByteArrayWriter cpWriter = new LEB128ByteArrayWriter(4096);

    cpWriter
        .writeLong(1L) // checkpoint event ID
        .writeLong(startNanos) // start timestamp
        .writeLong(System.nanoTime() - startTicks) // duration till now
        .writeLong(0L) // fake delta-to-next
        .writeInt(1) // all checkpoints are flush for now
        .writeInt(constantPools.size()); // start writing constant pools array

    for (ConstantPool cp : constantPools) {
      cp.writeTo(cpWriter);
    }

    writer
        .writeInt(cpWriter.length()) // write event size
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
        .writeLongRaw(startNanos)
        .writeLongRaw(0L)
        .writeLongRaw(startTicks)
        .writeLongRaw(1_000_000_000L) // 1 tick = 1 ns
        .writeIntRaw(1); // use compressed integers
  }

  private void writeMetadata(long duration) {
    writer.writeLongRaw(METADATA_OFFSET_OFFSET, writer.position());

    metadata.writeMetaEvent(writer, startTicks, duration);
  }

  void writeTypedValue(LEB128ByteArrayWriter writer, TypedValue value) {
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
        writeFields(writer, value);
      }
    }
  }

  private void writeFields(LEB128ByteArrayWriter writer, TypedValue value) {
    for (TypedFieldValue fieldValue : value.getFieldValues()) {
      if (fieldValue.getField().isArray()) {
        writer.writeInt(fieldValue.getValues().length); // array size
        for (TypedValue tValue : fieldValue.getValues()) {
          writeTypedValue(writer, tValue);
        }
      } else {
        writeTypedValue(writer, fieldValue.getValue());
      }
    }
  }

  private void writeBuiltinType(LEB128ByteArrayWriter writer, TypedValue typedValue) {
    Type type = typedValue.getType();
    Object value = typedValue.getValue();
    Types.Builtin builtin = Types.Builtin.ofType(type);
    if (builtin == null) {
      throw new IllegalArgumentException();
    }

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
              writer.writeCompactUTF((String) value);
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
}
