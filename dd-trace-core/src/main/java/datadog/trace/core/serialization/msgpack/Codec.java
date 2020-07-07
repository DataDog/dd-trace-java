package datadog.trace.core.serialization.msgpack;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;

public final class Codec extends ClassValue<Writer<?>> {

  public static final Codec INSTANCE = new Codec();

  private final Map<Class<?>, Writer<?>> config;

  public Codec(Map<Class<?>, Writer<?>> config) {
    this.config = config;
  }

  @SuppressWarnings("unchecked")
  public Codec() {
    this(Collections.<Class<?>, Writer<?>>emptyMap());
  }

  @Override
  protected Writer<?> computeValue(Class<?> clazz) {
    Writer<?> writer = config.get(clazz);
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

  private static final class IntArrayWriter implements Writer<int[]> {

    @Override
    public void write(int[] value, Packer packer, EncodingCache encodingCache) {
      packer.writeArrayHeader(value.length);
      for (int i : value) {
        packer.writeInt(i);
      }
    }
  }

  private static final class ShortArrayWriter implements Writer<short[]> {

    @Override
    public void write(short[] value, Packer packer, EncodingCache encodingCache) {
      packer.writeArrayHeader(value.length);
      for (short i : value) {
        packer.writeInt(i);
      }
    }
  }

  private static final class ByteArrayWriter implements Writer<byte[]> {

    @Override
    public void write(byte[] value, Packer packer, EncodingCache encodingCache) {
      packer.writeBinary(value, 0, value.length);
    }
  }

  private static final class ByteBufferWriter implements Writer<ByteBuffer> {

    @Override
    public void write(ByteBuffer buffer, Packer packer, EncodingCache encodingCache) {
      packer.writeBinary(buffer);
    }
  }

  private static final class BooleanArrayWriter implements Writer<boolean[]> {

    @Override
    public void write(boolean[] value, Packer packer, EncodingCache encodingCache) {
      packer.writeArrayHeader(value.length);
      for (boolean i : value) {
        packer.writeBoolean(i);
      }
    }
  }

  private static final class DoubleArrayWriter implements Writer<double[]> {

    @Override
    public void write(double[] value, Packer packer, EncodingCache encodingCache) {
      packer.writeArrayHeader(value.length);
      for (double i : value) {
        packer.writeDouble(i);
      }
    }
  }

  private static final class FloatArrayWriter implements Writer<float[]> {

    @Override
    public void write(float[] value, Packer packer, EncodingCache encodingCache) {
      packer.writeArrayHeader(value.length);
      for (float i : value) {
        packer.writeFloat(i);
      }
    }
  }

  private static final class LongArrayWriter implements Writer<long[]> {

    @Override
    public void write(long[] value, Packer packer, EncodingCache encodingCache) {
      packer.writeArrayHeader(value.length);
      for (long i : value) {
        packer.writeLong(i);
      }
    }
  }

  private static final class CollectionWriter implements Writer<Collection<?>> {

    @Override
    public void write(Collection<?> collection, Packer packer, EncodingCache encodingCache) {
      packer.writeArrayHeader(collection.size());
      for (Object value : collection) {
        packer.writeObject(value, encodingCache);
      }
    }
  }

  private static final class ObjectArrayWriter implements Writer<Object[]> {

    @Override
    public void write(Object[] array, Packer packer, EncodingCache encodingCache) {
      packer.writeArrayHeader(array.length);
      for (Object value : array) {
        packer.writeObject(value, encodingCache);
      }
    }
  }

  private static final class MapWriter implements Writer<Map<? extends CharSequence, Object>> {

    @Override
    public void write(
        Map<? extends CharSequence, Object> value, Packer packer, EncodingCache encodingCache) {
      packer.writeMap(value, encodingCache);
    }
  }

  private static final class DoubleWriter implements Writer<Double> {

    @Override
    public void write(Double value, Packer packer, EncodingCache encodingCache) {
      packer.writeDouble(value);
    }
  }

  private static final class BooleanWriter implements Writer<Boolean> {

    @Override
    public void write(Boolean value, Packer packer, EncodingCache encodingCache) {
      packer.writeBoolean(value);
    }
  }

  private static final class FloatWriter implements Writer<Float> {

    @Override
    public void write(Float value, Packer packer, EncodingCache encodingCache) {
      packer.writeFloat(value);
    }
  }

  private static final class IntWriter implements Writer<Integer> {

    @Override
    public void write(Integer value, Packer packer, EncodingCache encodingCache) {
      packer.writeInt(value);
    }
  }

  private static final class ShortWriter implements Writer<Short> {

    @Override
    public void write(Short value, Packer packer, EncodingCache encodingCache) {
      packer.writeInt(value);
    }
  }

  private static final class LongWriter implements Writer<Long> {

    @Override
    public void write(Long value, Packer packer, EncodingCache encodingCache) {
      packer.writeLong(value);
    }
  }

  private static final class CharSequenceWriter implements Writer<CharSequence> {

    public static final CharSequenceWriter INSTANCE = new CharSequenceWriter();

    @Override
    public void write(CharSequence value, Packer packer, EncodingCache encodingCache) {
      packer.writeString(value, encodingCache);
    }
  }

  private static final class CharArrayWriter implements Writer<char[]> {

    @Override
    public void write(char[] value, Packer packer, EncodingCache encodingCache) {
      packer.writeString(CharBuffer.wrap(value), EncodingCachingStrategies.NO_CACHING);
    }
  }

  private static final class DefaultWriter implements Writer<Object> {

    public static final DefaultWriter INSTANCE = new DefaultWriter();

    @Override
    public void write(Object value, Packer packer, EncodingCache encodingCache) {
      CharSequenceWriter.INSTANCE.write(
          String.valueOf(value), packer, EncodingCachingStrategies.NO_CACHING);
    }
  }
}
