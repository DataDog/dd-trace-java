package datadog.trace.codecoverage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongFunction;
import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.internal.data.CRC64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maintains a cache of {@link ClassProbeMapping} entries, keyed by CRC64 class ID. Builds entries
 * lazily when cache misses occur by resolving class bytes through the defining ClassLoader recorded
 * at transform time.
 */
final class ProbeMappingCache {

  private static final Logger log = LoggerFactory.getLogger(ProbeMappingCache.class);

  private final Map<Long, ClassProbeMapping> cache = new HashMap<>();

  /** Returns the cached mapping for the given class ID, or null if not cached. */
  ClassProbeMapping get(long classId) {
    return cache.get(classId);
  }

  /**
   * Populates cache entries for all classes in {@code missingClasses}. Resolves class bytes
   * primarily via the defining ClassLoader recorded at transform time, falling back to the system
   * and context classloaders.
   *
   * <p>Classes that cannot be resolved are not cached — they will be retried on subsequent
   * collection cycles (the defining classloader may become reachable later, e.g. after lazy module
   * initialization).
   *
   * @param missingClasses execution data entries that have no cached mapping
   * @param classLoaderLookup function that returns the recorded defining ClassLoader for a classId
   */
  void buildMissing(
      Collection<ExecutionData> missingClasses, LongFunction<ClassLoader> classLoaderLookup) {
    for (ExecutionData ed : missingClasses) {
      byte[] bytes = resolveClassBytes(ed.getName(), ed.getId(), classLoaderLookup);
      if (bytes == null) {
        log.debug(
            "Class {} (id {}) could not be resolved; will retry next cycle",
            ed.getName(),
            Long.toHexString(ed.getId()));
        continue;
      }
      try {
        ClassProbeMapping mapping =
            ClassProbeMappingBuilder.build(ed.getId(), ed.getName(), ed.getProbes().length, bytes);
        cache.put(ed.getId(), mapping);
      } catch (Exception e) {
        log.debug("Failed to build probe mapping for class {}", ed.getName(), e);
      }
    }
  }

  /**
   * Resolves the original class bytes for a given class. Tries the following sources in order:
   *
   * <ol>
   *   <li>The defining ClassLoader recorded at transform time (most reliable — works for custom
   *       classloaders, Spring Boot nested jars, OSGi, etc.)
   *   <li>The system classloader (standard application classpath)
   *   <li>The context classloader of the current thread
   * </ol>
   *
   * <p>CRC64 is verified to ensure the returned bytes match the version that was instrumented.
   *
   * @return the class bytes, or null if the class could not be resolved from any source
   */
  static byte[] resolveClassBytes(
      String className, long expectedClassId, LongFunction<ClassLoader> classLoaderLookup) {
    String resource = className + ".class";

    // 1. Try the defining classloader recorded at transform time
    ClassLoader definingLoader = classLoaderLookup.apply(expectedClassId);
    byte[] bytes = tryLoadResource(resource, expectedClassId, definingLoader);
    if (bytes != null) {
      return bytes;
    }

    // 2. Try system classloader
    bytes = tryLoadResource(resource, expectedClassId, ClassLoader.getSystemClassLoader());
    if (bytes != null) {
      return bytes;
    }

    // 3. Try context classloader
    bytes =
        tryLoadResource(resource, expectedClassId, Thread.currentThread().getContextClassLoader());
    return bytes;
  }

  /**
   * Attempts to load class bytes from the given classloader and verifies the CRC64 matches. Returns
   * null if the classloader is null, the resource is not found, or the CRC64 doesn't match.
   */
  private static byte[] tryLoadResource(String resource, long expectedClassId, ClassLoader loader) {
    if (loader == null) {
      return null;
    }
    InputStream is = loader.getResourceAsStream(resource);
    if (is == null) {
      return null;
    }
    try (InputStream stream = is) {
      byte[] bytes = readAllBytes(stream);
      long crc = CRC64.classId(bytes);
      if (crc != expectedClassId) {
        log.debug(
            "CRC64 mismatch for {} via {} (expected {}, got {})",
            resource,
            loader.getClass().getName(),
            Long.toHexString(expectedClassId),
            Long.toHexString(crc));
        return null;
      }
      return bytes;
    } catch (Exception e) {
      log.debug("Failed to read {} from {}", resource, loader.getClass().getName(), e);
      return null;
    }
  }

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
