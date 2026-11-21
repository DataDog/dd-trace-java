package com.datadog.openfeature.internal.evaluation;

import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.openfeature.internal.ufc.ServerConfiguration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EvaluationFeatureHandlesTest {
  private final ServerConfiguration configuration = configuration();

  @BeforeEach
  void startAnEpoch() {
    EvaluationFeatureHandles.configurationChanged(configuration);
  }

  @Test
  void aHandleResolvesToTheRegisteredMapUntilTheConfigurationChanges() {
    final Map<String, Object> features = EvaluationFeatureHandles.prepared(singletonMap("k", "v"));
    final long handle = EvaluationFeatureHandles.register(features);

    assertNotEquals(0L, handle);
    assertSame(features, EvaluationFeatureHandles.lookup(handle));

    EvaluationFeatureHandles.configurationChanged(configuration);
    assertSame(features, EvaluationFeatureHandles.lookup(handle), "same configuration, same epoch");

    EvaluationFeatureHandles.configurationChanged(configuration());
    assertNull(EvaluationFeatureHandles.lookup(handle));
  }

  @Test
  void handlesOutsideTheTableResolveToNull() {
    final long handle = EvaluationFeatureHandles.register(singletonMap("k", (Object) "v"));

    assertNull(EvaluationFeatureHandles.lookup(handle + 1));
    assertNull(EvaluationFeatureHandles.lookup(handle | 0xFFFF_FFFFL));
    assertNull(EvaluationFeatureHandles.lookup(0L));
  }

  @Test
  void theTableGrowsAndThenStopsAtItsBound() {
    final Map<String, Object> features = singletonMap("k", "v");
    long last = 0L;
    for (int i = 0; i < EvaluationFeatureHandles.MAX_HANDLES; i++) {
      last = EvaluationFeatureHandles.register(features);
      assertNotEquals(0L, last);
    }

    assertSame(features, EvaluationFeatureHandles.lookup(last));
    assertEquals(0L, EvaluationFeatureHandles.register(features));
  }

  @Test
  void preparedFeaturesAreImmutableAndEqualToTheirSource() {
    final Map<String, Object> source = new HashMap<>();
    source.put("a", "x");
    source.put("b", 1.0);
    final Map<String, Object> prepared = EvaluationFeatureHandles.prepared(source);

    assertEquals(source, prepared);
    assertEquals(prepared, source);
    assertEquals(source.hashCode(), prepared.hashCode());
    assertEquals(prepared, prepared);
    assertEquals("x", prepared.get("a"));
    assertTrue(prepared.containsKey("b"));
    assertEquals(2, prepared.size());
    assertThrows(UnsupportedOperationException.class, () -> prepared.put("c", "y"));
  }

  private static ServerConfiguration configuration() {
    return new ServerConfiguration(null, null, null, null, null);
  }
}
