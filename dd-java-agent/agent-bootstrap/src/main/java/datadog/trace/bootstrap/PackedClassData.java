package datadog.trace.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Reads indexed class-data chunks embedded in the agent jar. */
final class PackedClassData {
  static final String ENTRY_NAME = "dd-java-agent-common.classdata";
  static final String CHUNK_PREFIX = "dd-java-agent-common/";
  static final String CHUNK_SUFFIX = ".classdata";
  static final int MAGIC = 0x44444344; // DDCD
  static final int VERSION = 2;

  private static final int HEADER_SIZE = 20;
  private static final int RECORD_SIZE = 24;
  private static final int POST_BOOTSTRAP_CACHE_SIZE = 8;

  private final JarFile jarFile;
  private final Contents contents;
  private final URLStreamHandler resourceHandler = new PackedResourceHandler();

  static PackedClassData from(JarFile jarFile) throws IOException {
    JarEntry entry = jarFile.getJarEntry(ENTRY_NAME);
    return entry == null ? null : new PackedClassData(jarFile, entry);
  }

  private PackedClassData(JarFile jarFile, JarEntry indexEntry) throws IOException {
    this.jarFile = jarFile;
    contents = readContents(jarFile, indexEntry);
  }

  Slice find(String className) throws IOException {
    return contents.find(className);
  }

  InputStream openStream(String className) throws IOException {
    Slice slice = find(className);
    if (slice == null) {
      return null;
    }
    return new java.io.ByteArrayInputStream(slice.data, slice.offset, slice.length);
  }

  URL resource(String resourceName) {
    String className = className(resourceName);
    if (className == null || !contents.contains(className)) {
      return null;
    }
    try {
      return new URL(null, "dd-classdata:/" + resourceName, resourceHandler);
    } catch (Exception impossible) {
      throw new IllegalStateException("Unable to create packed class-data URL", impossible);
    }
  }

  int retainedChunkBytes() {
    return contents.retainedChunkBytes();
  }

  void release() {
    contents.release();
  }

  private static String className(String resourceName) {
    return resourceName.endsWith(".class")
        ? resourceName.substring(0, resourceName.length() - ".class".length()).replace('/', '.')
        : null;
  }

  static Index parseIndex(byte[] data) throws IOException {
    if (data.length < HEADER_SIZE) {
      throw new IOException("Truncated packed class-data index");
    }
    if (readInt(data, 0) != MAGIC) {
      throw new IOException("Bad packed class-data magic");
    }
    int version = readInt(data, 4);
    if (version != VERSION) {
      throw new IOException("Unsupported packed class-data version " + version);
    }
    int count = readInt(data, 8);
    int chunkCount = readInt(data, 12);
    int tableSize = readInt(data, 16);
    if (count < 0 || chunkCount <= 0 || tableSize <= 0 || (tableSize & (tableSize - 1)) != 0) {
      throw new IOException("Invalid packed class-data index dimensions");
    }
    if (count > tableSize || tableSize > (data.length - HEADER_SIZE) / RECORD_SIZE) {
      throw new IOException("Invalid packed class-data hash table size");
    }

    int namesOffset = HEADER_SIZE + tableSize * RECORD_SIZE;
    int populated = 0;
    int[] classesPerChunk = new int[chunkCount];
    for (int slot = 0; slot < tableSize; slot++) {
      int record = HEADER_SIZE + slot * RECORD_SIZE;
      int nameLength = readInt(data, record + 8);
      if (nameLength == 0) {
        continue;
      }
      int nameOffset = readInt(data, record + 4);
      int chunk = readInt(data, record + 12);
      int classOffset = readInt(data, record + 16);
      int classLength = readInt(data, record + 20);
      if (nameLength < 0
          || nameOffset < namesOffset
          || ((long) nameOffset + (long) nameLength * 2) > data.length
          || chunk < 0
          || chunk >= chunkCount
          || classOffset < 0
          || classLength < 0) {
        throw new IOException("Invalid packed class-data record");
      }
      populated++;
      classesPerChunk[chunk]++;
    }
    if (populated != count) {
      throw new IOException("Packed class-data entry count mismatch");
    }
    return new Index(data, count, chunkCount, tableSize, classesPerChunk);
  }

  private static Contents readContents(JarFile jarFile, JarEntry indexEntry) throws IOException {
    Index index = parseIndex(readEntry(jarFile, indexEntry));
    JarEntry[] chunkEntries = new JarEntry[index.chunkCount];
    for (int chunk = 0; chunk < chunkEntries.length; chunk++) {
      JarEntry entry = jarFile.getJarEntry(chunkEntryName(chunk));
      if (entry == null) {
        throw new IOException("Missing packed class-data chunk " + chunk);
      }
      chunkEntries[chunk] = entry;
    }
    index.validateChunkSizes(chunkEntries);
    return new Contents(jarFile, index, chunkEntries);
  }

  static String chunkEntryName(int chunk) {
    return CHUNK_PREFIX + chunk + CHUNK_SUFFIX;
  }

  private static byte[] readEntry(JarFile jarFile, JarEntry entry) throws IOException {
    long size = entry.getSize();
    if (size < 0 || size > Integer.MAX_VALUE) {
      throw new IOException("Invalid packed class-data size " + size);
    }
    byte[] data = new byte[(int) size];
    try (InputStream input = jarFile.getInputStream(entry)) {
      int offset = 0;
      while (offset < data.length) {
        int read = input.read(data, offset, data.length - offset);
        if (read < 0) {
          throw new IOException("Truncated packed class-data entry");
        }
        offset += read;
      }
      if (input.read() >= 0) {
        throw new IOException("Packed class-data entry exceeds declared size");
      }
    }
    return data;
  }

  private static int readInt(byte[] data, int offset) {
    return ((data[offset] & 0xff) << 24)
        | ((data[offset + 1] & 0xff) << 16)
        | ((data[offset + 2] & 0xff) << 8)
        | (data[offset + 3] & 0xff);
  }

  static int spread(int hash) {
    return hash ^ (hash >>> 16);
  }

  static final class Index {
    private final byte[] data;
    private final int count;
    private final int chunkCount;
    private final int tableSize;
    private final int[] classesPerChunk;

    private Index(byte[] data, int count, int chunkCount, int tableSize, int[] classesPerChunk) {
      this.data = data;
      this.count = count;
      this.chunkCount = chunkCount;
      this.tableSize = tableSize;
      this.classesPerChunk = classesPerChunk;
    }

    Location find(String className) {
      int hash = className.hashCode();
      int slot = spread(hash) & (tableSize - 1);
      for (int probes = 0; probes < tableSize; probes++) {
        int record = HEADER_SIZE + slot * RECORD_SIZE;
        int nameLength = readInt(data, record + 8);
        if (nameLength == 0) {
          return null;
        }
        if (readInt(data, record) == hash
            && nameLength == className.length()
            && nameEquals(data, readInt(data, record + 4), className)) {
          return new Location(
              readInt(data, record + 12), readInt(data, record + 16), readInt(data, record + 20));
        }
        slot = (slot + 1) & (tableSize - 1);
      }
      return null;
    }

    int size() {
      return count;
    }

    int chunkCount() {
      return chunkCount;
    }

    int classesInChunk(int chunk) {
      return classesPerChunk[chunk];
    }

    private void validateChunkSizes(JarEntry[] chunkEntries) throws IOException {
      for (int chunk = 0; chunk < classesPerChunk.length; chunk++) {
        if (classesPerChunk[chunk] == 0 || chunkEntries[chunk].getSize() < 0) {
          throw new IOException("Invalid packed class-data chunk " + chunk);
        }
      }
      for (int slot = 0; slot < tableSize; slot++) {
        int record = HEADER_SIZE + slot * RECORD_SIZE;
        if (readInt(data, record + 8) == 0) {
          continue;
        }
        int chunk = readInt(data, record + 12);
        int offset = readInt(data, record + 16);
        int length = readInt(data, record + 20);
        if ((long) offset + length > chunkEntries[chunk].getSize()) {
          throw new IOException("Packed class-data slice exceeds chunk " + chunk);
        }
      }
    }

    private static boolean nameEquals(byte[] data, int offset, String className) {
      for (int i = 0; i < className.length(); i++) {
        int value = ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff);
        if (value != className.charAt(i)) {
          return false;
        }
        offset += 2;
      }
      return true;
    }
  }

  static final class Location {
    final int chunk;
    final int offset;
    final int length;

    Location(int chunk, int offset, int length) {
      this.chunk = chunk;
      this.offset = offset;
      this.length = length;
    }
  }

  static final class Slice {
    final byte[] data;
    final int offset;
    final int length;
    final int chunk;

    Slice(byte[] data, int offset, int length, int chunk) {
      this.data = data;
      this.offset = offset;
      this.length = length;
      this.chunk = chunk;
    }
  }

  private static final class Contents {
    private final JarFile jarFile;
    private final Index index;
    private final JarEntry[] chunkEntries;
    private final AtomicReferenceArray<byte[]> chunks;
    private final Object[] chunkLocks;
    private final Object cacheLock = new Object();
    private final long[] lastAccess;
    private volatile boolean released;
    private long accessCounter;

    private Contents(JarFile jarFile, Index index, JarEntry[] chunkEntries) {
      this.jarFile = jarFile;
      this.index = index;
      this.chunkEntries = chunkEntries;
      chunks = new AtomicReferenceArray<>(chunkEntries.length);
      chunkLocks = new Object[chunkEntries.length];
      for (int chunk = 0; chunk < chunkLocks.length; chunk++) {
        chunkLocks[chunk] = new Object();
      }
      lastAccess = new long[chunkEntries.length];
    }

    private boolean contains(String className) {
      return index.find(className) != null;
    }

    private Slice find(String className) throws IOException {
      Location location = index.find(className);
      if (location == null) {
        return null;
      }
      byte[] chunk = chunks.get(location.chunk);
      if (chunk == null) {
        synchronized (chunkLocks[location.chunk]) {
          chunk = chunks.get(location.chunk);
          if (chunk == null) {
            chunk = readEntry(jarFile, chunkEntries[location.chunk]);
            chunks.set(location.chunk, chunk);
          }
        }
      }
      if (released) {
        recordPostBootstrapAccess(location.chunk, chunk);
      }
      if ((long) location.offset + location.length > chunk.length) {
        throw new IOException("Packed class-data slice exceeds chunk " + location.chunk);
      }
      return new Slice(chunk, location.offset, location.length, location.chunk);
    }

    private int retainedChunkBytes() {
      int bytes = 0;
      for (int index = 0; index < chunks.length(); index++) {
        byte[] chunk = chunks.get(index);
        if (chunk != null) {
          bytes += chunk.length;
        }
      }
      return bytes;
    }

    private void release() {
      synchronized (cacheLock) {
        released = true;
        for (int chunk = 0; chunk < chunks.length(); chunk++) {
          chunks.set(chunk, null);
          lastAccess[chunk] = 0;
        }
      }
    }

    private void recordPostBootstrapAccess(int loadedChunk, byte[] loadedContents) {
      synchronized (cacheLock) {
        // release() may have cleared this chunk after find() obtained its local reference.
        if (chunks.get(loadedChunk) != loadedContents) {
          return;
        }
        lastAccess[loadedChunk] = ++accessCounter;
        evictPostBootstrapChunk(loadedChunk);
      }
    }

    private void evictPostBootstrapChunk(int loadedChunk) {
      int retained = 0;
      int oldestChunk = -1;
      long oldestAccess = Long.MAX_VALUE;
      for (int chunk = 0; chunk < chunks.length(); chunk++) {
        if (chunks.get(chunk) != null) {
          retained++;
          if (chunk != loadedChunk && lastAccess[chunk] < oldestAccess) {
            oldestChunk = chunk;
            oldestAccess = lastAccess[chunk];
          }
        }
      }
      if (retained > POST_BOOTSTRAP_CACHE_SIZE && oldestChunk >= 0) {
        chunks.set(oldestChunk, null);
        lastAccess[oldestChunk] = 0;
      }
    }
  }

  private final class PackedResourceHandler extends URLStreamHandler {
    @Override
    protected URLConnection openConnection(URL url) {
      return new URLConnection(url) {
        @Override
        public void connect() {}

        @Override
        public InputStream getInputStream() throws IOException {
          String resourceName = url.getPath().substring(1);
          InputStream input = openStream(className(resourceName));
          if (input == null) {
            throw new IOException("Missing packed class resource " + resourceName);
          }
          return input;
        }
      };
    }
  }
}
