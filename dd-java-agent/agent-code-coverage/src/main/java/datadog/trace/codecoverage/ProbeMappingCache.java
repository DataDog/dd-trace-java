package datadog.trace.codecoverage;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
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
   * Populates cache entries for all classes in {@code missingClasses}. First attempts targeted
   * lookup via the context classloader's {@code getResourceAsStream} (O(1) per class). Any classes
   * that can't be resolved this way fall back to a full classpath scan.
   *
   * @param missingClasses execution data entries that have no cached mapping
   * @param classpathEntries jars/directories to scan as fallback
   */
  void buildMissing(Collection<ExecutionData> missingClasses, List<File> classpathEntries) {
    // Build a lookup: classId -> ExecutionData for the missing entries
    Map<Long, ExecutionData> needed = new HashMap<>();
    for (ExecutionData ed : missingClasses) {
      needed.put(ed.getId(), ed);
    }

    // Phase 1: targeted classloader lookup (fast path)
    resolveViaClassloader(needed);

    // Phase 2: fall back to classpath scan for anything still unresolved
    if (!needed.isEmpty()) {
      resolveViaClasspathScan(needed, classpathEntries);
    }

    // Any remaining entries couldn't be found anywhere.
    // Mark them with a sentinel so we don't retry on subsequent cycles.
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

  /**
   * Attempts to resolve missing classes via the context classloader's resource lookup. This is O(1)
   * per class — the classloader already knows where each class file lives. CRC64 is verified after
   * reading to ensure the bytes match what JaCoCo instrumented.
   */
  private void resolveViaClassloader(Map<Long, ExecutionData> needed) {
    // Try the system classloader (application classpath) first, then the context classloader.
    // The dd-code-coverage thread inherits the agent's context classloader, which typically
    // can't find application classes. The system classloader is the standard app classloader.
    ClassLoader systemCl = ClassLoader.getSystemClassLoader();
    ClassLoader contextCl = Thread.currentThread().getContextClassLoader();

    // Iterate over a copy since we modify 'needed' during iteration
    for (ExecutionData ed : new ArrayList<>(needed.values())) {
      String resource = ed.getName() + ".class";
      InputStream is = findResource(resource, systemCl, contextCl);
      if (is == null) {
        continue; // not found via any classloader — will try classpath scan
      }
      try (InputStream stream = is) {
        byte[] bytes = readAllBytes(stream);
        long crc = CRC64.classId(bytes);
        if (crc != ed.getId()) {
          // CRC64 mismatch — classloader returned different bytes than what was instrumented.
          // Fall through to classpath scan.
          log.debug(
              "CRC64 mismatch for {} via classloader (expected {}, got {}); will try classpath scan",
              ed.getName(),
              Long.toHexString(ed.getId()),
              Long.toHexString(crc));
          continue;
        }
        ClassProbeMapping mapping =
            ClassProbeMappingBuilder.build(
                ed.getId(), ed.getName(), ed.getProbes().length, bytes);
        cache.put(ed.getId(), mapping);
        needed.remove(ed.getId());
      } catch (Exception e) {
        log.debug("Failed to resolve class {} via classloader", ed.getName(), e);
      }
    }
  }

  /**
   * Tries to find a class resource using the given classloaders, returning the first non-null
   * InputStream. Returns null if no classloader can find the resource.
   */
  private static InputStream findResource(
      String resource, ClassLoader... classLoaders) {
    for (ClassLoader cl : classLoaders) {
      if (cl == null) {
        continue;
      }
      InputStream is = cl.getResourceAsStream(resource);
      if (is != null) {
        return is;
      }
    }
    return null;
  }

  /**
   * Falls back to scanning classpath jars/directories for classes that couldn't be resolved via the
   * classloader.
   */
  private void resolveViaClasspathScan(
      Map<Long, ExecutionData> needed, List<File> classpathEntries) {
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
