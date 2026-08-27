package datadog.trace.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackedClassDataTest {
  private static final int HEADER_SIZE = 20;
  private static final int RECORD_SIZE = 24;

  @TempDir Path temporaryDirectory;

  @Test
  void findsIndexedLocationsIncludingCollisionsAndUnicode() throws Exception {
    // FB and Ea have the same String hash code.
    PackedClassData.Index index =
        PackedClassData.parseIndex(
            packIndex(
                2,
                entry("example.FB", 0, 1, 3),
                entry("example.Ea", 0, 4, 5),
                entry("example.Éclair", 1, 2, 7)));

    assertEquals(3, index.size());
    assertEquals(2, index.chunkCount());
    assertEquals(2, index.classesInChunk(0));
    assertEquals(1, index.classesInChunk(1));
    assertLocation(index.find("example.FB"), 0, 1, 3);
    assertLocation(index.find("example.Ea"), 0, 4, 5);
    assertLocation(index.find("example.Éclair"), 1, 2, 7);
    assertNull(index.find("example.Missing"));
  }

  @Test
  void rejectsMalformedIndex() throws Exception {
    byte[] valid = packIndex(1, entry("example.First", 0, 0, 3));

    byte[] badMagic = valid.clone();
    badMagic[0] = 0;
    assertThrows(IOException.class, () -> PackedClassData.parseIndex(badMagic));
    assertThrows(
        IOException.class, () -> PackedClassData.parseIndex(Arrays.copyOf(valid, HEADER_SIZE - 1)));

    byte[] badCount = valid.clone();
    writeInt(badCount, 8, 2);
    assertThrows(IOException.class, () -> PackedClassData.parseIndex(badCount));

    byte[] badChunk = valid.clone();
    int record = occupiedRecord(badChunk);
    writeInt(badChunk, record + 12, 1);
    assertThrows(IOException.class, () -> PackedClassData.parseIndex(badChunk));
  }

  @Test
  void rejectsMissingAndOutOfBoundsChunksWhenOpened() throws Exception {
    byte[] index = packIndex(1, entry("example.First", 0, 0, 3));
    Path missingPath = temporaryDirectory.resolve("missing.jar");
    try (OutputStream file = Files.newOutputStream(missingPath);
        JarOutputStream jar = new JarOutputStream(file)) {
      writeEntry(jar, PackedClassData.ENTRY_NAME, index);
    }
    try (JarFile jar = new JarFile(missingPath.toFile())) {
      assertThrows(IOException.class, () -> PackedClassData.from(jar));
    }

    Path shortPath = temporaryDirectory.resolve("short.jar");
    try (OutputStream file = Files.newOutputStream(shortPath);
        JarOutputStream jar = new JarOutputStream(file)) {
      writeEntry(jar, PackedClassData.ENTRY_NAME, index);
      writeEntry(jar, PackedClassData.chunkEntryName(0), new byte[] {1, 2});
    }
    try (JarFile jar = new JarFile(shortPath.toFile())) {
      assertThrows(IOException.class, () -> PackedClassData.from(jar));
    }
  }

  @Test
  void keepsAChunkForTheCompleteBootstrapPhase() throws Exception {
    byte[] index = packIndex(1, entry("example.First", 0, 0, 3), entry("example.Second", 0, 3, 2));
    Path jarPath = temporaryDirectory.resolve("packed.jar");
    try (OutputStream file = Files.newOutputStream(jarPath);
        JarOutputStream jar = new JarOutputStream(file)) {
      writeEntry(jar, PackedClassData.ENTRY_NAME, index);
      writeEntry(jar, PackedClassData.chunkEntryName(0), new byte[] {1, 2, 3, 4, 5});
    }

    try (JarFile jar = new JarFile(jarPath.toFile())) {
      PackedClassData packed = PackedClassData.from(jar);
      PackedClassData.Slice first = packed.find("example.First");
      PackedClassData.Slice second = packed.find("example.Second");
      assertSame(first.data, second.data);

      assertSame(first.data, packed.find("example.First").data);

      PackedClassData.Slice reloaded = packed.find("example.First");
      assertSame(first.data, reloaded.data);
      assertSame(reloaded.data, packed.find("example.First").data);
      assertEquals(5, packed.retainedChunkBytes());
    }
  }

  @Test
  void exposesPackedClassesAsLazyResources() throws Exception {
    byte[] index = packIndex(1, entry("example.First", 0, 0, 3));
    Path jarPath = temporaryDirectory.resolve("resources.jar");
    try (OutputStream file = Files.newOutputStream(jarPath);
        JarOutputStream jar = new JarOutputStream(file)) {
      writeEntry(jar, PackedClassData.ENTRY_NAME, index);
      writeEntry(jar, PackedClassData.chunkEntryName(0), new byte[] {1, 2, 3});
    }

    try (JarFile jar = new JarFile(jarPath.toFile())) {
      PackedClassData packed = PackedClassData.from(jar);
      URL resource = packed.resource("example/First.class");
      assertNotNull(resource);
      assertEquals(0, packed.retainedChunkBytes());
      assertEquals(1, resource.openStream().read());
      assertEquals(3, packed.retainedChunkBytes());
      packed.release();
      assertEquals(0, packed.retainedChunkBytes());
      assertNull(packed.resource("example/missing.txt"));
    }
  }

  @Test
  void releasesRetainedChunksAtTheEndOfBootstrap() throws Exception {
    byte[] index =
        packIndex(
            5,
            entry("example.First", 0, 0, 3),
            entry("example.Second", 1, 0, 1),
            entry("example.Third", 2, 0, 1),
            entry("example.Fourth", 3, 0, 1),
            entry("example.Later", 4, 0, 2));
    Path jarPath = temporaryDirectory.resolve("ordered.jar");
    try (OutputStream file = Files.newOutputStream(jarPath);
        JarOutputStream jar = new JarOutputStream(file)) {
      writeEntry(jar, PackedClassData.ENTRY_NAME, index);
      writeEntry(jar, PackedClassData.chunkEntryName(0), new byte[] {1, 2, 3});
      writeEntry(jar, PackedClassData.chunkEntryName(1), new byte[] {4});
      writeEntry(jar, PackedClassData.chunkEntryName(2), new byte[] {5});
      writeEntry(jar, PackedClassData.chunkEntryName(3), new byte[] {6});
      writeEntry(jar, PackedClassData.chunkEntryName(4), new byte[] {7, 8});
    }

    try (JarFile jar = new JarFile(jarPath.toFile())) {
      PackedClassData packed = PackedClassData.from(jar);
      PackedClassData.Slice first = packed.find("example.First");
      assertEquals(3, packed.retainedChunkBytes());

      PackedClassData.Slice later = packed.find("example.Later");
      assertEquals(5, packed.retainedChunkBytes());

      packed.release();
      assertEquals(0, packed.retainedChunkBytes());
      PackedClassData.Slice firstAfterBootstrap = packed.find("example.First");
      PackedClassData.Slice laterAfterBootstrap = packed.find("example.Later");
      assertNotSame(first.data, firstAfterBootstrap.data);
      assertNotSame(later.data, laterAfterBootstrap.data);
      assertSame(firstAfterBootstrap.data, packed.find("example.First").data);
      assertEquals(5, packed.retainedChunkBytes());
    }
  }

  @Test
  void boundsThePostBootstrapChunkCache() throws Exception {
    Entry[] entries = new Entry[9];
    for (int chunk = 0; chunk < entries.length; chunk++) {
      entries[chunk] = entry("example.Class" + chunk, chunk, 0, 1);
    }
    Path jarPath = temporaryDirectory.resolve("bounded.jar");
    try (OutputStream file = Files.newOutputStream(jarPath);
        JarOutputStream jar = new JarOutputStream(file)) {
      writeEntry(jar, PackedClassData.ENTRY_NAME, packIndex(entries.length, entries));
      for (int chunk = 0; chunk < entries.length; chunk++) {
        writeEntry(jar, PackedClassData.chunkEntryName(chunk), new byte[] {(byte) chunk});
      }
    }

    try (JarFile jar = new JarFile(jarPath.toFile())) {
      PackedClassData packed = PackedClassData.from(jar);
      packed.release();
      PackedClassData.Slice first = packed.find("example.Class0");
      for (int chunk = 1; chunk < entries.length; chunk++) {
        packed.find("example.Class" + chunk);
      }
      assertEquals(8, packed.retainedChunkBytes());
      assertNotSame(first.data, packed.find("example.Class0").data);
      assertEquals(8, packed.retainedChunkBytes());
    }
  }

  private static void assertLocation(
      PackedClassData.Location location, int chunk, int offset, int length) {
    assertEquals(chunk, location.chunk);
    assertEquals(offset, location.offset);
    assertEquals(length, location.length);
  }

  private static Entry entry(String name, int chunk, int offset, int length) {
    return new Entry(name, chunk, offset, length);
  }

  private static byte[] packIndex(int chunkCount, Entry... entries) {
    int tableSize = 1;
    while (tableSize < entries.length * 2) {
      tableSize <<= 1;
    }
    int namesOffset = HEADER_SIZE + tableSize * RECORD_SIZE;
    int namesLength = 0;
    for (Entry entry : entries) {
      namesLength += entry.name.length() * 2;
    }
    byte[] index = new byte[namesOffset + namesLength];
    writeInt(index, 0, PackedClassData.MAGIC);
    writeInt(index, 4, PackedClassData.VERSION);
    writeInt(index, 8, entries.length);
    writeInt(index, 12, chunkCount);
    writeInt(index, 16, tableSize);

    boolean[] occupied = new boolean[tableSize];
    int nextName = namesOffset;
    for (Entry entry : entries) {
      int slot = PackedClassData.spread(entry.name.hashCode()) & (tableSize - 1);
      while (occupied[slot]) {
        slot = (slot + 1) & (tableSize - 1);
      }
      occupied[slot] = true;
      int record = HEADER_SIZE + slot * RECORD_SIZE;
      writeInt(index, record, entry.name.hashCode());
      writeInt(index, record + 4, nextName);
      writeInt(index, record + 8, entry.name.length());
      writeInt(index, record + 12, entry.chunk);
      writeInt(index, record + 16, entry.offset);
      writeInt(index, record + 20, entry.length);
      for (int i = 0; i < entry.name.length(); i++) {
        char character = entry.name.charAt(i);
        index[nextName++] = (byte) (character >>> 8);
        index[nextName++] = (byte) character;
      }
    }
    return index;
  }

  private static int occupiedRecord(byte[] index) {
    int tableSize =
        ((index[16] & 0xff) << 24)
            | ((index[17] & 0xff) << 16)
            | ((index[18] & 0xff) << 8)
            | (index[19] & 0xff);
    for (int slot = 0; slot < tableSize; slot++) {
      int record = HEADER_SIZE + slot * RECORD_SIZE;
      if (index[record + 8] != 0
          || index[record + 9] != 0
          || index[record + 10] != 0
          || index[record + 11] != 0) {
        return record;
      }
    }
    throw new AssertionError("No occupied record");
  }

  private static void writeEntry(JarOutputStream jar, String name, byte[] contents)
      throws IOException {
    jar.putNextEntry(new JarEntry(name));
    jar.write(contents);
    jar.closeEntry();
  }

  private static void writeInt(byte[] target, int offset, int value) {
    target[offset] = (byte) (value >>> 24);
    target[offset + 1] = (byte) (value >>> 16);
    target[offset + 2] = (byte) (value >>> 8);
    target[offset + 3] = (byte) value;
  }

  private static final class Entry {
    private final String name;
    private final int chunk;
    private final int offset;
    private final int length;

    private Entry(String name, int chunk, int offset, int length) {
      this.name = name;
      this.chunk = chunk;
      this.offset = offset;
      this.length = length;
    }
  }
}
