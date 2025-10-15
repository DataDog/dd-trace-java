package datadog.communication.serialization;

import datadog.communication.serialization.custom.aiguard.FunctionWriter;
import datadog.communication.serialization.custom.aiguard.MessageWriter;
import datadog.communication.serialization.custom.aiguard.ToolCallWriter;
import datadog.communication.serialization.custom.stacktrace.StackTraceEventFrameWriter;
import datadog.communication.serialization.custom.stacktrace.StackTraceEventWriter;
import datadog.trace.api.Config;
import datadog.trace.api.aiguard.AIGuard;
import datadog.trace.util.stacktrace.StackTraceEvent;
import datadog.trace.util.stacktrace.StackTraceFrame;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class Codec extends ClassValue<ValueWriter<?>> {

  public static final Codec INSTANCE;

  static {
    final Map<Class<?>, ValueWriter<?>> writers = new HashMap<>(1 << 3);
    writers.put(StackTraceEvent.class, new StackTraceEventWriter());
    writers.put(StackTraceFrame.class, new StackTraceEventFrameWriter());
    if (Config.get().isAiGuardEnabled()) {
      writers.put(AIGuard.Message.class, new MessageWriter());
      writers.put(AIGuard.ToolCall.class, new ToolCallWriter());
      writers.put(AIGuard.ToolCall.Function.class, new FunctionWriter());
    }
    INSTANCE = new Codec(writers);
  }

  private final Map<Class<?>, ValueWriter<?>> config;

  public Codec(Map<Class<?>, ValueWriter<?>> config) {
    this.config = config;
  }

  @SuppressWarnings("unchecked")
  public Codec() {
    this(Collections.<Class<?>, ValueWriter<?>>emptyMap());
  }

  @Override
  protected ValueWriter<?> computeValue(Class<?> clazz) {
    ValueWriter<?> writer = config.get(clazz);
    if (null != writer) {
      return writer;
    }
    if (Number.class.isAssignableFrom(clazz)) {
      if (Double.class == clazz) {
        return new DoubleWriter();
      }
      if (Float.class == clazz) {
        return new FloatWriter();
      }
      if (Integer.class == clazz) {
        return new IntWriter();
      }
      if (Long.class == clazz) {
        return new LongWriter();
      }
      if (Short.class == clazz) {
        return new ShortWriter();
      }
      // This is some other Number type, that will be treated as a metric by the
      // serializer, so let's write out its double value to be protocol compatible
      return new NumberDoubleWriter();
    }
    if (clazz.isArray()) {
      if (byte[].class == clazz) {
        return new ByteArrayWriter();
      }
      if (int[].class == clazz) {
        return new IntArrayWriter();
      }
      if (long[].class == clazz) {
        return new LongArrayWriter();
      }
      if (double[].class == clazz) {
        return new DoubleArrayWriter();
      }
      if (float[].class == clazz) {
        return new FloatArrayWriter();
      }
      if (short[].class == clazz) {
        return new ShortArrayWriter();
      }
      if (char[].class == clazz) {
        return new CharArrayWriter();
      }
      if (boolean[].class == clazz) {
        return new BooleanArrayWriter();
      }
      return new ObjectArrayWriter();
    }
    if (Boolean.class == clazz) {
      return new BooleanWriter();
    }
    if (CharSequence.class.isAssignableFrom(clazz)) {
      return CharSequenceWriter.INSTANCE;
    }
    if (Map.class.isAssignableFrom(clazz)) {
      return new MapWriter();
    }
    if (Collection.class.isAssignableFrom(clazz)) {
      return new CollectionWriter();
    }
    if (ByteBuffer.class.isAssignableFrom(clazz)) {
      return new ByteBufferWriter();
    }
    return DefaultWriter.INSTANCE;
  }

  private static final class IntArrayWriter implements ValueWriter<int[]> {

    @Override
    public void write(int[] value, Writable packer, EncodingCache encodingCache) {
      packer.startArray(value.length);
      for (int i : value) {
        packer.writeInt(i);
      }
    }
  }

  private static final class ShortArrayWriter implements ValueWriter<short[]> {

    @Override
    public void write(short[] value, Writable packer, EncodingCache encodingCache) {
      packer.startArray(value.length);
      for (short i : value) {
        packer.writeInt(i);
      }
    }
  }

  private static final class ByteArrayWriter implements ValueWriter<byte[]> {

    @Override
    public void write(byte[] value, Writable packer, EncodingCache encodingCache) {
      packer.writeBinary(value, 0, value.length);
    }
  }

  private static final class ByteBufferWriter implements ValueWriter<ByteBuffer> {

    @Override
    public void write(ByteBuffer buffer, Writable packer, EncodingCache encodingCache) {
      packer.writeBinary(buffer);
    }
  }

  private static final class BooleanArrayWriter implements ValueWriter<boolean[]> {

    @Override
    public void write(boolean[] value, Writable packer, EncodingCache encodingCache) {
      packer.startArray(value.length);
      for (boolean i : value) {
        packer.writeBoolean(i);
      }
    }
  }

  private static final class DoubleArrayWriter implements ValueWriter<double[]> {

    @Override
    public void write(double[] value, Writable packer, EncodingCache encodingCache) {
      packer.startArray(value.length);
      for (double i : value) {
        packer.writeDouble(i);
      }
    }
  }

  private static final class FloatArrayWriter implements ValueWriter<float[]> {

    @Override
    public void write(float[] value, Writable packer, EncodingCache encodingCache) {
      packer.startArray(value.length);
      for (float i : value) {
        packer.writeFloat(i);
      }
    }
  }

  private static final class LongArrayWriter implements ValueWriter<long[]> {

    @Override
    public void write(long[] value, Writable packer, EncodingCache encodingCache) {
      packer.startArray(value.length);
      for (long i : value) {
        packer.writeLong(i);
      }
    }
  }

  private static final class CollectionWriter implements ValueWriter<Collection<?>> {

    @Override
    public void write(Collection<?> collection, Writable packer, EncodingCache encodingCache) {
      packer.startArray(collection.size());
      for (Object value : collection) {
        packer.writeObject(value, encodingCache);
      }
    }
  }

  private static final class ObjectArrayWriter implements ValueWriter<Object[]> {

    @Override
    public void write(Object[] array, Writable packer, EncodingCache encodingCache) {
      packer.startArray(array.length);
      for (Object value : array) {
        packer.writeObject(value, encodingCache);
      }
    }
  }

  private static final class MapWriter implements ValueWriter<Map<? extends CharSequence, Object>> {

    @Override
    public void write(
        Map<? extends CharSequence, Object> value, Writable packer, EncodingCache encodingCache) {
      packer.writeMap(value, encodingCache);
    }
  }

  private static final class DoubleWriter implements ValueWriter<Double> {

    @Override
    public void write(Double value, Writable packer, EncodingCache encodingCache) {
      packer.writeDouble(value);
    }
  }

  private static final class BooleanWriter implements ValueWriter<Boolean> {

    @Override
    public void write(Boolean value, Writable packer, EncodingCache encodingCache) {
      packer.writeBoolean(value);
    }
  }

  private static final class FloatWriter implements ValueWriter<Float> {

    @Override
    public void write(Float value, Writable packer, EncodingCache encodingCache) {
      packer.writeFloat(value);
    }
  }

  private static final class IntWriter implements ValueWriter<Integer> {

    @Override
    public void write(Integer value, Writable packer, EncodingCache encodingCache) {
      packer.writeInt(value);
    }
  }

  private static final class ShortWriter implements ValueWriter<Short> {

    @Override
    public void write(Short value, Writable packer, EncodingCache encodingCache) {
      packer.writeInt(value);
    }
  }

  private static final class LongWriter implements ValueWriter<Long> {

    @Override
    public void write(Long value, Writable packer, EncodingCache encodingCache) {
      packer.writeLong(value);
    }
  }

  private static final class NumberDoubleWriter implements ValueWriter<Number> {

    @Override
    public void write(Number value, Writable packer, EncodingCache encodingCache) {
      packer.writeDouble(value.doubleValue());
    }
  }

  private static final class CharSequenceWriter implements ValueWriter<CharSequence> {

    public static final CharSequenceWriter INSTANCE = new CharSequenceWriter();

    @Override
    public void write(CharSequence value, Writable packer, EncodingCache encodingCache) {
      packer.writeString(value, encodingCache);
    }
  }

  private static final class CharArrayWriter implements ValueWriter<char[]> {

    @Override
    public void write(char[] value, Writable packer, EncodingCache encodingCache) {
      packer.writeString(CharBuffer.wrap(value), null);
    }
  }

  private static final class DefaultWriter implements ValueWriter<Object> {

    public static final DefaultWriter INSTANCE = new DefaultWriter();

    @Override
    public void write(Object value, Writable packer, EncodingCache encodingCache) {
      CharSequenceWriter.INSTANCE.write(String.valueOf(value), packer, null);
    }
  }
}
