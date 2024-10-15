package datadog.communication.serialization;

import datadog.trace.util.stacktrace.StackTraceBatch;
import datadog.trace.util.stacktrace.StackTraceEvent;
import datadog.trace.util.stacktrace.StackTraceFrame;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Codec extends ClassValue<ValueWriter<?>> {

  private static final Logger LOGGER = LoggerFactory.getLogger(Codec.class);

  public static final Codec INSTANCE = new Codec();

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
    if (StackTraceBatch.class.isAssignableFrom(clazz)) {
      return new StackTraceBatchWriter();
    }
    if (StackTraceEvent.class.isAssignableFrom(clazz)) {
      return new StackTraceEventWriter();
    }
    if (StackTraceFrame.class.isAssignableFrom(clazz)) {
      return new StackTraceEventFrameWriter();
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

  private static final class StackTraceBatchWriter implements ValueWriter<StackTraceBatch> {

    @Override
    public void write(StackTraceBatch value, Writable writable, EncodingCache encodingCache) {
      int mapSize = 0;
      boolean hasExploits = value.getExploit() != null && !value.getExploit().isEmpty();
      boolean hasVulnerabilities =
          value.getVulnerability() != null && !value.getVulnerability().isEmpty();
      if (hasExploits) {
        mapSize++;
      }
      if (hasVulnerabilities) {
        mapSize++;
      }
      if (mapSize == 0) {
        LOGGER.warn("No data to serialize in StackTraceBatch");
        return; // This should never happen
      }
      writable.startMap(mapSize);
      if (hasExploits) {
        writable.writeString("exploit", encodingCache);
        writable.writeObject(value.getExploit(), encodingCache);
      }
      if (hasVulnerabilities) {
        writable.writeString("vulnerability", encodingCache);
        writable.writeObject(value.getVulnerability(), encodingCache);
      }
    }
  }

  private static final class StackTraceEventWriter implements ValueWriter<StackTraceEvent> {

    @Override
    public void write(StackTraceEvent value, Writable writable, EncodingCache encodingCache) {
      int mapSize = 1; // frames always present
      boolean hasId = value.getId() != null && !value.getId().isEmpty();
      boolean hasLanguage = value.getLanguage() != null && !value.getLanguage().isEmpty();
      boolean hasMessage = value.getMessage() != null && !value.getMessage().isEmpty();
      if (hasId) {
        mapSize++;
      }
      if (hasLanguage) {
        mapSize++;
      }
      if (hasMessage) {
        mapSize++;
      }
      writable.startMap(mapSize);
      if (hasId) {
        writable.writeString("id", encodingCache);
        writable.writeString(value.getId(), encodingCache);
      }
      if (hasLanguage) {
        writable.writeString("language", encodingCache);
        writable.writeString(value.getLanguage(), encodingCache);
      }
      if (hasMessage) {
        writable.writeString("message", encodingCache);
        writable.writeString(value.getMessage(), encodingCache);
      }
      writable.writeString("frames", encodingCache);
      writable.writeObject(value.getFrames(), encodingCache);
    }
  }

  private static final class StackTraceEventFrameWriter implements ValueWriter<StackTraceFrame> {

    @Override
    public void write(StackTraceFrame value, Writable writable, EncodingCache encodingCache) {
      int mapSize = 1; // id always present
      boolean hasText = value.getText() != null && !value.getText().isEmpty();
      boolean hasFile = value.getFile() != null && !value.getFile().isEmpty();
      boolean hasLine = value.getLine() != null;
      boolean hasClass = value.getClass_name() != null && !value.getClass_name().isEmpty();
      boolean hasFunction = value.getFunction() != null && !value.getFunction().isEmpty();
      if (hasText) {
        mapSize++;
      }
      if (hasFile) {
        mapSize++;
      }
      if (hasLine) {
        mapSize++;
      }
      if (hasClass) {
        mapSize++;
      }
      if (hasFunction) {
        mapSize++;
      }
      writable.startMap(mapSize);
      writable.writeString("id", encodingCache);
      writable.writeInt(value.getId());
      if (hasText) {
        writable.writeString("text", encodingCache);
        writable.writeString(value.getText(), encodingCache);
      }
      if (hasFile) {
        writable.writeString("file", encodingCache);
        writable.writeString(value.getFile(), encodingCache);
      }
      if (hasLine) {
        writable.writeString("line", encodingCache);
        writable.writeInt(value.getLine());
      }
      if (hasClass) {
        writable.writeString("class_name", encodingCache);
        writable.writeString(value.getClass_name(), encodingCache);
      }
      if (hasFunction) {
        writable.writeString("function", encodingCache);
        writable.writeString(value.getFunction(), encodingCache);
      }
    }
  }
}
