package datadog.trace.civisibility.coverage.line;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import datadog.trace.api.civisibility.config.TestIdentifier;
import datadog.trace.civisibility.coverage.line.LineCoverageStore.AnalysisCacheKey;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class LineCoverageStoreTest {

  @Test
  void cacheKeyReusesAnalysisForSameClassAndProbes() {
    AnalysisCacheKey key = new AnalysisCacheKey(1L, new boolean[] {true, false, true, false});
    AnalysisCacheKey same = new AnalysisCacheKey(1L, new boolean[] {true, false, true, false});
    // identical class id + probe set must collide so the analysis is reused
    assertEquals(key, same);
    assertEquals(key.hashCode(), same.hashCode());
  }

  @Test
  void cacheKeyDistinguishesClassesAndProbeSets() {
    AnalysisCacheKey key = new AnalysisCacheKey(1L, new boolean[] {true, false, true});
    // a different class or a different probe set must NOT hit the same cache entry
    assertNotEquals(key, new AnalysisCacheKey(2L, new boolean[] {true, false, true}));
    assertNotEquals(key, new AnalysisCacheKey(1L, new boolean[] {true, true, true}));
  }

  @Test
  void cacheKeyIgnoresTrailingUnsetProbes() {
    // The key bit-packs the activated probe set; trailing probes that never fire don't change
    // coverage, so padding differences must not create distinct entries.
    AnalysisCacheKey shortKey = new AnalysisCacheKey(1L, new boolean[] {true, false, true});
    AnalysisCacheKey padded =
        new AnalysisCacheKey(1L, new boolean[] {true, false, true, false, false});
    assertEquals(shortKey, padded);
    assertEquals(shortKey.hashCode(), padded.hashCode());
  }

  @Test
  void recordingLoadsNoClassesOnceTheFactoryExists() throws Exception {
    // A covered defineClass hook records coverage while a class loads. If recording itself loaded
    // a class, it would run the hook again and recurse.
    try (RecordingLoader loader = new RecordingLoader()) {
      Class<?> factoryType = loader.loadClass(LineCoverageStore.Factory.class.getName());
      Object factory = factoryType.getConstructors()[0].newInstance(null, null);
      factoryType
          .getMethod("setTotalProbeCount", String.class, int.class)
          .invoke(factory, "java/lang/String", 1);
      Set<String> loadedByFactory = new HashSet<>(loader.defined);

      Object store =
          factoryType.getMethod("create", TestIdentifier.class).invoke(factory, (Object) null);
      Object probes = store.getClass().getMethod("getProbes").invoke(store);
      probes
          .getClass()
          .getMethod("record", Class.class, long.class, int.class)
          .invoke(probes, String.class, 1L, 0);

      assertEquals(loadedByFactory, new HashSet<>(loader.defined));
    }
  }

  /** Defines this package's classes itself, so the test sees exactly when each one loads. */
  private static final class RecordingLoader extends URLClassLoader {
    private static final String PACKAGE = LineCoverageStore.class.getPackage().getName() + ".";

    final List<String> defined = new CopyOnWriteArrayList<>();

    RecordingLoader() {
      super(
          new URL[] {LineCoverageStore.class.getProtectionDomain().getCodeSource().getLocation()},
          LineCoverageStoreTest.class.getClassLoader());
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
      if (!name.startsWith(PACKAGE)) {
        return super.loadClass(name, resolve);
      }
      synchronized (getClassLoadingLock(name)) {
        Class<?> type = findLoadedClass(name);
        if (type == null) {
          type = findClass(name);
          defined.add(name);
        }
        return type;
      }
    }
  }
}
