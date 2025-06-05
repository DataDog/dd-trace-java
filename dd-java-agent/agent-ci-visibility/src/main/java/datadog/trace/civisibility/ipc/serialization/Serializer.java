package datadog.trace.civisibility.ipc.serialization;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class Serializer {

  private final ByteArrayOutputStream baos = new ByteArrayOutputStream();

  public void write(byte b) {
    baos.write(b);
  }

  public void write(boolean b) {
    baos.write((byte) (b ? 1 : 0));
  }

  public void write(int i) {
    baos.write(i >> 24);
    baos.write(i >> 16);
    baos.write(i >> 8);
    baos.write(i);
  }

  public void write(long l) {
    baos.write((int) (l >> 56));
    baos.write((int) (l >> 48));
    baos.write((int) (l >> 40));
    baos.write((int) (l >> 32));
    baos.write((int) (l >> 24));
    baos.write((int) (l >> 16));
    baos.write((int) (l >> 8));
    baos.write((int) l);
  }

  public void write(String s) {
    if (s == null) {
      write(-1);
      return;
    }
    write(s.getBytes(StandardCharsets.UTF_8));
  }

  public void write(byte[] b) {
    if (b == null) {
      write(-1);
      return;
    }
    write(b.length);
    baos.write(b, 0, b.length);
  }

  public void write(Collection<String> c) {
    write(c, Serializer::write);
  }

  public <T> void write(Collection<T> c, BiConsumer<Serializer, T> elementSerializer) {
    if (c == null) {
      write(-1);
      return;
    }
    write(c.size());
    for (T t : c) {
      elementSerializer.accept(this, t);
    }
  }

  public void write(Map<String, String> m) {
    write(m, Serializer::write, Serializer::write);
  }

  public <K, V> void write(
      Map<K, V> m,
      BiConsumer<Serializer, K> keySerializer,
      BiConsumer<Serializer, V> valueSerializer) {
    if (m == null) {
      write(-1);
      return;
    }
    write(m.size());
    for (Map.Entry<K, V> e : m.entrySet()) {
      keySerializer.accept(this, e.getKey());
      valueSerializer.accept(this, e.getValue());
    }
  }

  public void write(BitSet bitSet) {
    if (bitSet != null) {
      write(bitSet.toByteArray());
    } else {
      write((byte[]) null);
    }
  }

  public int length() {
    return baos.size();
  }

  public ByteBuffer flush() {
    return ByteBuffer.wrap(baos.toByteArray());
  }

  public void flush(ByteBuffer byteBuffer) {
    byteBuffer.put(baos.toByteArray());
  }

  public static byte readByte(ByteBuffer byteBuffer) {
    return byteBuffer.get();
  }

  public static boolean readBoolean(ByteBuffer byteBuffer) {
    byte b = byteBuffer.get();
    return b == 1;
  }

  public static int readInt(ByteBuffer byteBuffer) {
    return byteBuffer.getInt();
  }

  public static long readLong(ByteBuffer byteBuffer) {
    return byteBuffer.getLong();
  }

  public static String readString(ByteBuffer byteBuffer) {
    byte[] b = readByteArray(byteBuffer);
    return b != null ? new String(b, StandardCharsets.UTF_8) : null;
  }

  public static byte[] readByteArray(ByteBuffer byteBuffer) {
    int length = byteBuffer.getInt();
    if (length == -1) {
      return null;
    }
    byte[] b = new byte[length];
    byteBuffer.get(b);
    return b;
  }

  public static List<String> readStringList(ByteBuffer byteBuffer) {
    return readList(byteBuffer, Serializer::readString);
  }

  public static <T> List<T> readList(
      ByteBuffer byteBuffer, Function<ByteBuffer, T> elementDeserializer) {
    int size = byteBuffer.getInt();
    if (size == -1) {
      return null;
    }
    List<T> c = new ArrayList<>(size);
    for (int i = 0; i < size; i++) {
      c.add(elementDeserializer.apply(byteBuffer));
    }
    return c;
  }

  public static <T> Set<T> readSet(
      ByteBuffer byteBuffer, Function<ByteBuffer, T> elementDeserializer) {
    List<T> list = readList(byteBuffer, elementDeserializer);
    return list != null ? new HashSet<>(list) : null;
  }

  public static Map<String, String> readStringMap(ByteBuffer byteBuffer) {
    return readMap(byteBuffer, Serializer::readString, Serializer::readString);
  }

  public static <K, V> Map<K, V> readMap(
      ByteBuffer byteBuffer,
      Function<ByteBuffer, K> keyDeserializer,
      Function<ByteBuffer, V> valueDeserializer) {
    int size = byteBuffer.getInt();
    if (size == -1) {
      return null;
    }
    Map<K, V> m = new HashMap<>(size * 4 / 3);
    return fillMap(byteBuffer, m, keyDeserializer, valueDeserializer, size);
  }

  public static <K, V> Map<K, V> readMap(
      ByteBuffer byteBuffer,
      Supplier<Map<K, V>> mapSupplier,
      Function<ByteBuffer, K> keyDeserializer,
      Function<ByteBuffer, V> valueDeserializer) {
    int size = byteBuffer.getInt();
    if (size == -1) {
      return null;
    }
    Map<K, V> m = mapSupplier.get();
    return fillMap(byteBuffer, m, keyDeserializer, valueDeserializer, size);
  }

  private static <K, V> Map<K, V> fillMap(
      ByteBuffer byteBuffer,
      Map<K, V> m,
      Function<ByteBuffer, K> keyDeserializer,
      Function<ByteBuffer, V> valueDeserializer,
      int size) {
    for (int i = 0; i < size; i++) {
      m.put(keyDeserializer.apply(byteBuffer), valueDeserializer.apply(byteBuffer));
    }
    return m;
  }

  public static BitSet readBitSet(ByteBuffer byteBuffer) {
    byte[] bytes = readByteArray(byteBuffer);
    return bytes != null ? BitSet.valueOf(bytes) : null;
  }
}
