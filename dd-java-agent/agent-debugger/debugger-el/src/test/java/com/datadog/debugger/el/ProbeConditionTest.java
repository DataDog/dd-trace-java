package com.datadog.debugger.el;

import static java.util.Collections.singletonMap;
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
    ValueReferenceResolver ctx = RefResolverHelper.createResolver(null, singletonMap("tags", tags));

    assertTrue(probeCondition.execute(ctx));

    Collection<String> tags2 = Arrays.asList("hey", "world", "ko");
    ValueReferenceResolver ctx2 =
        RefResolverHelper.createResolver(null, singletonMap("tags", tags2));
    assertFalse(probeCondition.execute(ctx2));
  }

  @Test
  void testGetMember() throws Exception {
    ProbeCondition probeCondition = load("/test_conditional_04.json");

    ValueReferenceResolver ctx =
        RefResolverHelper.createResolver(
            singletonMap("container", new Container("world")),
            singletonMap("container", new Container("hello")));

    assertTrue(probeCondition.execute(ctx));

    ValueReferenceResolver ctx2 =
        RefResolverHelper.createResolver(
            singletonMap("container", new Container("world")),
            singletonMap("obj", new Container("hello")));
    assertFalse(probeCondition.execute(ctx2));
  }

  @Test
  void testComparisonOperators() throws Exception {
    ProbeCondition probeCondition = load("/test_conditional_05.json");
    ValueReferenceResolver ctx =
        RefResolverHelper.createResolver(null, singletonMap("intField1", 42));
    assertTrue(probeCondition.execute(ctx));
  }

  @Test
  void testNullLiteral() throws Exception {
    ProbeCondition probeCondition = load("/test_conditional_06.json");
    ValueReferenceResolver ctx =
        RefResolverHelper.createResolver(
            singletonMap("nullField", null), singletonMap("objField", new Object()));
    assertTrue(probeCondition.execute(ctx));
  }

  @Test
  void testIndex() throws Exception {
    ProbeCondition probeCondition = load("/test_conditional_07.json");
    Map<String, Object> fields = new HashMap<>();
    fields.put("intArray", new int[] {1, 1, 1});
    fields.put("strArray", new String[] {"foo", "bar"});
    Map<String, String> strMap = new HashMap<>();
    strMap.put("foo", "bar");
    strMap.put("bar", "foobar");
    fields.put("strMap", strMap);
    fields.put("idx", 1);
    ValueReferenceResolver ctx = RefResolverHelper.createResolver(null, fields);
    assertTrue(probeCondition.execute(ctx));
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
    assertEquals("{\"dsl\":\"\"}", jsonAdapter.toJson(ProbeCondition.NONE));
  }

  @Test
  void testJsonParsing() throws IOException {
    ProbeCondition probeCondition = load("/test_conditional_02.json");
    Collection<String> vets = Arrays.asList("vet1", "vet2", "vet3");
    ValueReferenceResolver ctx = RefResolverHelper.createResolver(null, singletonMap("vets", vets));

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

  @Test
  void testIncorrectSyntax() {
    UnsupportedOperationException ex =
        assertThrows(
            UnsupportedOperationException.class, () -> load("/test_conditional_03_error.json"));
    assertEquals("Unsupported operation 'gte'", ex.getMessage());
  }

  private static ProbeCondition load(String resourcePath) throws IOException {
    InputStream input = ProbeConditionTest.class.getResourceAsStream(resourcePath);
    Moshi moshi =
        new Moshi.Builder()
            .add(ProbeCondition.class, new ProbeCondition.ProbeConditionJsonAdapter())
            .build();
    return moshi.adapter(ProbeCondition.class).fromJson(Okio.buffer(Okio.source(input)));
  }

  static class Container {
    String msg;

    public Container(String msg) {
      this.msg = msg;
    }
  }
}
