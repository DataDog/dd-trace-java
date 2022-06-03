package com.datadog.debugger.el;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import datadog.trace.bootstrap.debugger.el.ValueReferenceResolver;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import okio.Okio;
import org.junit.jupiter.api.Test;

public class ProbeConditionTest {
  // used in testExecuteCondition
  private int field = 10;

  @Test
  void testExecuteCondition() throws Exception {
    ProbeCondition probeCondition = load("/test_conditional_01.json");

    Collection<String> tags = Arrays.asList("hello", "world", "ko");
    ValueReferenceResolver ctx =
        new StaticValueRefResolver(this, 100, null, Collections.singletonMap("#tags", tags));

    assertTrue(probeCondition.execute(ctx));

    Collection<String> tags2 = Arrays.asList("hey", "world", "ko");
    ValueReferenceResolver ctx2 =
        new StaticValueRefResolver(this, 100, null, Collections.singletonMap("#tags", tags2));
    assertFalse(probeCondition.execute(ctx2));
  }

  @Test
  void testJsonAdapter() throws IOException {
    Moshi moshi =
        new Moshi.Builder()
            .add(ProbeCondition.class, new ProbeCondition.ProbeConditionJsonAdapter())
            .build();
    JsonAdapter<ProbeCondition> jsonAdapter = moshi.adapter(ProbeCondition.class);
    assertNull(jsonAdapter.fromJson("null"));
    assertEquals(jsonAdapter.toJson(null), "null");
    assertThrows(
        UnsupportedOperationException.class, () -> jsonAdapter.toJson(ProbeCondition.NONE));
  }

  @Test
  void testJsonParsing() throws IOException {
    ProbeCondition probeCondition = load("/test_conditional_02.json");
    Collection<String> vets = Arrays.asList("vet1", "vet2", "vet3");
    ValueReferenceResolver ctx =
        new StaticValueRefResolver(this, 100, null, Collections.singletonMap("#vets", vets));

    // the condition checks if length of vets > 2
    assertTrue(probeCondition.execute(ctx));
  }

  @Test
  void testEquals() throws IOException {
    ProbeCondition probeCondition1 = load("/test_conditional_01.json");
    ProbeCondition probeCondition2 = load("/test_conditional_01.json");

    assertEquals(probeCondition1, probeCondition1);
    assertEquals(probeCondition1, probeCondition2);
    assertNotEquals(probeCondition1, ProbeCondition.NONE);
    assertNotEquals(probeCondition1, null);
  }

  @Test
  void testHashCode() throws IOException {
    ProbeCondition probeCondition1 = load("/test_conditional_01.json");
    ProbeCondition probeCondition2 = load("/test_conditional_01.json");

    Map<ProbeCondition, Boolean> map = new HashMap<>();
    map.put(probeCondition1, true);

    assertTrue(map.get(probeCondition2));
    assertNull(map.get(ProbeCondition.NONE));
  }

  private static ProbeCondition load(String resourcePath) throws IOException {
    InputStream input = ProbeConditionTest.class.getResourceAsStream(resourcePath);
    Moshi moshi =
        new Moshi.Builder()
            .add(ProbeCondition.class, new ProbeCondition.ProbeConditionJsonAdapter())
            .build();
    return moshi.adapter(ProbeCondition.class).fromJson(Okio.buffer(Okio.source(input)));
  }
}
