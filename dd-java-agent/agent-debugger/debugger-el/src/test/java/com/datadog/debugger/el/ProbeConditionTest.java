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
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import okio.Okio;
import org.junit.jupiter.api.Test;

public class ProbeConditionTest {
  // used in testExecuteCondition
  private int field = 10;

  @Test
  void testExecuteCondition() throws Exception {
    ProbeCondition probeCondition = load("/test_conditional_01.json");

    Collection<String> tags = Arrays.asList("hello", "world", "ko");
    Map<String, Object> fields = new HashMap<>();
    fields.put("tags", tags);
    fields.put("field", 10);
    class Obj {
      List<String> field2 = new ArrayList<>();
    }
    ValueReferenceResolver ctx =
        RefResolverHelper.createResolver(singletonMap("this", new Obj()), null, fields);

    assertTrue(probeCondition.execute(ctx));

    Collection<String> tags2 = Arrays.asList("hey", "world", "ko");
    fields = new HashMap<>();
    fields.put("tags", tags2);
    fields.put("field", 10);
    ValueReferenceResolver ctx2 =
        RefResolverHelper.createResolver(singletonMap("this", new Obj()), null, fields);
    assertFalse(probeCondition.execute(ctx2));
  }

  @Test
  void testGetMember() throws Exception {
    ProbeCondition probeCondition = load("/test_conditional_04.json");
    class Obj {
      Container container = new Container("hello");
    }
    ValueReferenceResolver ctx =
        RefResolverHelper.createResolver(
            singletonMap("this", new Obj()),
            singletonMap("container", new Container("world")),
            null);

    assertTrue(probeCondition.execute(ctx));
    class Obj2 {
      Container obj = new Container("hello");
    }
    ValueReferenceResolver ctx2 =
        RefResolverHelper.createResolver(
            singletonMap("this", new Obj2()),
            singletonMap("container", new Container("world")),
            null);
    RuntimeException runtimeException =
        assertThrows(RuntimeException.class, () -> probeCondition.execute(ctx2));
    assertEquals("Cannot dereference to field: container", runtimeException.getMessage());
  }

  @Test
  void testComparisonOperators() throws Exception {
    ProbeCondition probeCondition = load("/test_conditional_05.json");
    class Obj {
      int intField1 = 42;
      String strField = "foo";
    }
    Obj obj = new Obj();
    Map<String, Object> fields = new HashMap<>();
    fields.put("intField1", obj.intField1);
    fields.put("strField", obj.strField);
    ValueReferenceResolver ctx =
        RefResolverHelper.createResolver(singletonMap("this", obj), null, fields);
    assertTrue(probeCondition.execute(ctx));
  }

  @Test
  void testNullLiteral() throws Exception {
    ProbeCondition probeCondition = load("/test_conditional_06.json");
    class Obj {
      Object objField = new Object();
    }
    ValueReferenceResolver ctx =
        RefResolverHelper.createResolver(
            singletonMap("this", new Obj()), singletonMap("nullField", null), null);
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
    ValueReferenceResolver ctx = RefResolverHelper.createResolver(null, null, fields);
    assertTrue(probeCondition.execute(ctx));
  }

  @Test
  void testStringOperation() throws Exception {
    ProbeCondition probeCondition = load("/test_conditional_08.json");
    Map<String, Object> fields = new HashMap<>();
    fields.put("strField", "foobar");
    ValueReferenceResolver ctx = RefResolverHelper.createResolver(null, null, fields);
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
    ValueReferenceResolver ctx =
        RefResolverHelper.createResolver(null, null, singletonMap("vets", vets));

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

  @Test
  void redaction() throws IOException {
    ProbeCondition probeCondition = load("/test_conditional_09.json");
    Map<String, Object> args = new HashMap<>();
    args.put("password", "secret123");
    ValueReferenceResolver ctx = RefResolverHelper.createResolver(args, null, null);
    EvaluationException evaluationException =
        assertThrows(EvaluationException.class, () -> probeCondition.execute(ctx));
    assertEquals(
        "Could not evaluate the expression because 'password' was redacted",
        evaluationException.getMessage());
  }

  @Test
  void stringPrimitives() throws IOException {
    ProbeCondition probeCondition = load("/test_conditional_10.json");
    Map<String, Object> args = new HashMap<>();
    args.put("uuid", UUID.fromString("a3cbe9e7-edd3-4bef-8e5b-59bfcb04cf91"));
    args.put("duration", Duration.ofSeconds(42));
    args.put("clazz", "foo".getClass());
    ValueReferenceResolver ctx = RefResolverHelper.createResolver(args, null, null);
    assertTrue(probeCondition.execute(ctx));
  }

  @Test
  void testBooleanOperation() throws Exception {
    ProbeCondition probeCondition = load("/test_conditional_11.json");
    Map<String, Object> fields = new HashMap<>();
    fields.put("strField", "foobar");
    fields.put("emptyStr", "");
    fields.put("emptyList", new ArrayList<>());
    fields.put("emptyMap", new HashMap<>());
    fields.put("emptyArray", new Object[0]);
    ValueReferenceResolver ctx = RefResolverHelper.createResolver(null, null, fields);
    assertTrue(probeCondition.execute(ctx));
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
