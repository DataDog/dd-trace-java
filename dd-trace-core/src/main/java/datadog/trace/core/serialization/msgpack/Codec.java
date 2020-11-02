package datadog.trace.core.serialization.msgpack;

import datadog.trace.core.serialization.EncodingCache;
import datadog.trace.core.serialization.EncodingCachingStrategies;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;

public final class Codec extends ClassValue<MsgPackWriter<?>> {

  public static final Codec INSTANCE = new Codec();

  private final Map<Class<?>, MsgPackWriter<?>> config;

  public Codec(Map<Class<?>, MsgPackWriter<?>> config) {
    this.config = config;
  }

  @SuppressWarnings("unchecked")
  public Codec() {
    this(Collections.<Class<?>, MsgPackWriter<?>>emptyMap());
  }

  @Override
  protected MsgPackWriter<?> computeValue(Class<?> clazz) {
    MsgPackWriter<?> writer = config.get(clazz);
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

  private static final class IntArrayWriter implements MsgPackWriter<int[]> {

    @Override
    public void write(int[] value, MsgPacker packer, EncodingCache encodingCache) {
      packer.writeArrayHeader(value.length);
      for (int i : value) {
        packer.writeInt(i);
      }
    }
  }

  private static final class ShortArrayWriter implements MsgPackWriter<short[]> {

    @Override
    public void write(short[] value, MsgPacker packer, EncodingCache encodingCache) {
      packer.writeArrayHeader(value.length);
      for (short i : value) {
        packer.writeInt(i);
      }
    }
  }

  private static final class ByteArrayWriter implements MsgPackWriter<byte[]> {

    @Override
    public void write(byte[] value, MsgPacker packer, EncodingCache encodingCache) {
      packer.writeBinary(value, 0, value.length);
    }
  }

  private static final class ByteBufferWriter implements MsgPackWriter<ByteBuffer> {

    @Override
    public void write(ByteBuffer buffer, MsgPacker packer, EncodingCache encodingCache) {
      packer.writeBinary(buffer);
    }
  }

  private static final class BooleanArrayWriter implements MsgPackWriter<boolean[]> {

    @Override
    public void write(boolean[] value, MsgPacker packer, EncodingCache encodingCache) {
      packer.writeArrayHeader(value.length);
      for (boolean i : value) {
        packer.writeBoolean(i);
      }
    }
  }

  private static final class DoubleArrayWriter implements MsgPackWriter<double[]> {

    @Override
    public void write(double[] value, MsgPacker packer, EncodingCache encodingCache) {
      packer.writeArrayHeader(value.length);
      for (double i : value) {
        packer.writeDouble(i);
      }
    }
  }

  private static final class FloatArrayWriter implements MsgPackWriter<float[]> {

    @Override
    public void write(float[] value, MsgPacker packer, EncodingCache encodingCache) {
      packer.writeArrayHeader(value.length);
      for (float i : value) {
        packer.writeFloat(i);
      }
    }
  }

  private static final class LongArrayWriter implements MsgPackWriter<long[]> {

    @Override
    public void write(long[] value, MsgPacker packer, EncodingCache encodingCache) {
      packer.writeArrayHeader(value.length);
      for (long i : value) {
        packer.writeLong(i);
      }
    }
  }

  private static final class CollectionWriter implements MsgPackWriter<Collection<?>> {

    @Override
    public void write(Collection<?> collection, MsgPacker packer, EncodingCache encodingCache) {
      packer.writeArrayHeader(collection.size());
      for (Object value : collection) {
        packer.writeObject(value, encodingCache);
      }
    }
  }

  private static final class ObjectArrayWriter implements MsgPackWriter<Object[]> {

    @Override
    public void write(Object[] array, MsgPacker packer, EncodingCache encodingCache) {
      packer.writeArrayHeader(array.length);
      for (Object value : array) {
        packer.writeObject(value, encodingCache);
      }
    }
  }

  private static final class MapWriter
      implements MsgPackWriter<Map<? extends CharSequence, Object>> {

    @Override
    public void write(
        Map<? extends CharSequence, Object> value, MsgPacker packer, EncodingCache encodingCache) {
      packer.writeMap(value, encodingCache);
    }
  }

  private static final class DoubleWriter implements MsgPackWriter<Double> {

    @Override
    public void write(Double value, MsgPacker packer, EncodingCache encodingCache) {
      packer.writeDouble(value);
    }
  }

  private static final class BooleanWriter implements MsgPackWriter<Boolean> {

    @Override
    public void write(Boolean value, MsgPacker packer, EncodingCache encodingCache) {
      packer.writeBoolean(value);
    }
  }

  private static final class FloatWriter implements MsgPackWriter<Float> {

    @Override
    public void write(Float value, MsgPacker packer, EncodingCache encodingCache) {
      packer.writeFloat(value);
    }
  }

  private static final class IntWriter implements MsgPackWriter<Integer> {

    @Override
    public void write(Integer value, MsgPacker packer, EncodingCache encodingCache) {
      packer.writeInt(value);
    }
  }

  private static final class ShortWriter implements MsgPackWriter<Short> {

    @Override
    public void write(Short value, MsgPacker packer, EncodingCache encodingCache) {
      packer.writeInt(value);
    }
  }

  private static final class LongWriter implements MsgPackWriter<Long> {

    @Override
    public void write(Long value, MsgPacker packer, EncodingCache encodingCache) {
      packer.writeLong(value);
    }
  }

  private static final class CharSequenceWriter implements MsgPackWriter<CharSequence> {

    public static final CharSequenceWriter INSTANCE = new CharSequenceWriter();

    @Override
    public void write(CharSequence value, MsgPacker packer, EncodingCache encodingCache) {
      packer.writeString(value, encodingCache);
    }
  }

  private static final class CharArrayWriter implements MsgPackWriter<char[]> {

    @Override
    public void write(char[] value, MsgPacker packer, EncodingCache encodingCache) {
      packer.writeString(CharBuffer.wrap(value), EncodingCachingStrategies.NO_CACHING);
    }
  }

  private static final class DefaultWriter implements MsgPackWriter<Object> {

    public static final DefaultWriter INSTANCE = new DefaultWriter();

    @Override
    public void write(Object value, MsgPacker packer, EncodingCache encodingCache) {
      CharSequenceWriter.INSTANCE.write(
          String.valueOf(value), packer, EncodingCachingStrategies.NO_CACHING);
    }
  }
}
