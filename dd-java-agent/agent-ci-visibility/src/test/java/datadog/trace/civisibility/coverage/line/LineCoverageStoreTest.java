package datadog.trace.civisibility.coverage.line;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import datadog.trace.civisibility.coverage.line.LineCoverageStore.AnalysisCacheKey;
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
}
