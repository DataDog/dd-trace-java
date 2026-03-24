package datadog.trace.codecoverage;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.internal.data.CRC64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maintains a cache of {@link ClassProbeMapping} entries, keyed by CRC64 class ID. Builds entries
 * lazily from classpath analysis when cache misses occur.
 */
final class ProbeMappingCache {

  private static final Logger log = LoggerFactory.getLogger(ProbeMappingCache.class);

  private final Map<Long, ClassProbeMapping> cache = new HashMap<>();

  /** Returns the cached mapping for the given class ID, or null if not cached. */
  ClassProbeMapping get(long classId) {
    return cache.get(classId);
  }

  /**
   * Populates cache entries for all classes in {@code missingClasses} by scanning the given
   * classpath entries. Each classpath entry is a jar or directory.
   *
   * <p>For each class file found, computes CRC64 and checks if it matches any missing class. If
   * so, builds a ClassProbeMapping and adds it to the cache.
   *
   * @param missingClasses execution data entries that have no cached mapping
   * @param classpathEntries jars/directories to scan for class files
   */
  void buildMissing(Collection<ExecutionData> missingClasses, List<File> classpathEntries) {
    // Build a lookup: classId -> ExecutionData for the missing entries
    Map<Long, ExecutionData> needed = new HashMap<>();
    for (ExecutionData ed : missingClasses) {
      needed.put(ed.getId(), ed);
    }

    for (File entry : classpathEntries) {
      if (needed.isEmpty()) {
        break;
      }
      if (!entry.exists()) {
        continue;
      }

      try {
        if (entry.isDirectory()) {
          scanDirectory(entry, needed);
        } else if (entry.getName().endsWith(".jar") || entry.getName().endsWith(".zip")) {
          scanJar(entry, needed);
        }
      } catch (IOException e) {
        log.debug("Failed to scan classpath entry for cache building: {}", entry, e);
      }
    }

    // Any remaining entries in 'needed' couldn't be found on the classpath.
    // Mark them in the cache with a sentinel so we don't rescan for them.
    for (Map.Entry<Long, ExecutionData> e : needed.entrySet()) {
      cache.put(
          e.getKey(),
              new ClassProbeMapping(e.getKey(), null, null, new BitSet(), new int[0][]));
      log.debug(
          "Class {} (id {}) not found on classpath; skipping",
          e.getValue().getName(),
          Long.toHexString(e.getKey()));
    }
  }

  private void scanDirectory(File dir, Map<Long, ExecutionData> needed) throws IOException {
    File[] files = dir.listFiles();
    if (files == null) {
      return;
    }
    for (File f : files) {
      if (needed.isEmpty()) {
        return;
      }
      if (f.isDirectory()) {
        scanDirectory(f, needed);
      } else if (f.getName().endsWith(".class")) {
        // Use try-with-resources to avoid leaking the FileInputStream
        try (FileInputStream fis = new FileInputStream(f)) {
          byte[] bytes = readAllBytes(fis);
          tryBuildMapping(bytes, needed);
        }
      }
    }
  }

  private void scanJar(File jarFile, Map<Long, ExecutionData> needed) throws IOException {
    try (ZipInputStream zis = new ZipInputStream(new FileInputStream(jarFile))) {
      ZipEntry entry;
      while ((entry = zis.getNextEntry()) != null && !needed.isEmpty()) {
        if (entry.getName().endsWith(".class") && !entry.isDirectory()) {
          // Do NOT close the ZipInputStream here -- readAllBytes reads without closing
          byte[] bytes = readAllBytes(zis);
          tryBuildMapping(bytes, needed);
        }
      }
    }
  }

  private void tryBuildMapping(byte[] classBytes, Map<Long, ExecutionData> needed) {
    long crc = CRC64.classId(classBytes);
    ExecutionData ed = needed.get(crc);
    if (ed == null) {
      return; // this class isn't one we're looking for
    }

    try {
      ClassProbeMapping mapping =
          ClassProbeMappingBuilder.build(
              ed.getId(), ed.getName(), ed.getProbes().length, classBytes);
      cache.put(ed.getId(), mapping);
      needed.remove(crc);
    } catch (Exception e) {
      log.debug("Failed to build probe mapping for class {}", ed.getName(), e);
    }
  }

  /**
   * Reads all bytes from an input stream WITHOUT closing it (important for ZipInputStream where
   * closing the stream would close the zip).
   */
  private static byte[] readAllBytes(InputStream is) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buf = new byte[4096];
    int r;
    while ((r = is.read(buf)) != -1) {
      out.write(buf, 0, r);
    }
    return out.toByteArray();
  }
}
