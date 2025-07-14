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
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import okio.Okio;
import org.junit.jupiter.api.Test;

public class ProbeConditionTest {
  // used in testExecuteCondition
  private int field = 10;

  @Test
  void testExecuteCondition() throws Exception {
    ProbeCondition probeCondition = loadFromResource("/test_conditional_01.json");
    class Obj1 {
      Collection<String> tags = Arrays.asList("hello", "world", "ko");
      private int field = 10;
      List<String> field2 = new ArrayList<>();
    }
    ValueReferenceResolver ctx = RefResolverHelper.createResolver(new Obj1());

    assertTrue(probeCondition.execute(ctx));

    class Obj2 {
      Collection<String> tags = Arrays.asList("hey", "world", "ko");
      private int field = 10;
      List<String> field2 = new ArrayList<>();
    }
    ValueReferenceResolver ctx2 = RefResolverHelper.createResolver(new Obj2());
    assertFalse(probeCondition.execute(ctx2));
  }

  @Test
  void testGetMember() throws Exception {
    ProbeCondition probeCondition = loadFromResource("/test_conditional_04.json");
    class Obj {
      Container container = new Container("hello");
    }
    ValueReferenceResolver ctx =
        RefResolverHelper.createResolver(
            singletonMap("this", new Obj()), singletonMap("container", new Container("world")));

    assertTrue(probeCondition.execute(ctx));
    class Obj2 {
      Container obj = new Container("hello");
    }
    ValueReferenceResolver ctx2 =
        RefResolverHelper.createResolver(
            singletonMap("this", new Obj2()), singletonMap("container", new Container("world")));
    RuntimeException runtimeException =
        assertThrows(RuntimeException.class, () -> probeCondition.execute(ctx2));
    assertEquals("Cannot dereference field: container", runtimeException.getMessage());
  }

  @Test
  void testComparisonOperators() throws Exception {
    ProbeCondition probeCondition = loadFromResource("/test_conditional_05.json");
    class Obj {
      int intField1 = 42;
      String strField = "foo";
    }
    ValueReferenceResolver ctx = RefResolverHelper.createResolver(new Obj());
    assertTrue(probeCondition.execute(ctx));
  }

  @Test
  void testNullLiteral() throws Exception {
    ProbeCondition probeCondition = loadFromResource("/test_conditional_06.json");
    class Obj {
      Object objField = new Object();
    }
    ValueReferenceResolver ctx =
        RefResolverHelper.createResolver(
            singletonMap("this", new Obj()), singletonMap("nullField", null));
    assertTrue(probeCondition.execute(ctx));
  }

  @Test
  void testIndex() throws Exception {
    ProbeCondition probeCondition = loadFromResource("/test_conditional_07.json");
    Map<String, String> strMap = new HashMap<>();
    class Obj {
      int[] intArray = new int[] {1, 1, 1};
      String[] strArray = new String[] {"foo", "bar"};
      Map<String, String> strMap = new HashMap<>();

      {
        strMap.put("foo", "bar");
        strMap.put("bar", "foobar");
      }

      int idx = 1;
    }
    ValueReferenceResolver ctx = RefResolverHelper.createResolver(new Obj());
    assertTrue(probeCondition.execute(ctx));
  }

  @Test
  void testStringOperation() throws Exception {
    ProbeCondition probeCondition = loadFromResource("/test_conditional_08.json");
    class Obj {
      String strField = "foobar";
    }
    ValueReferenceResolver ctx = RefResolverHelper.createResolver(new Obj());
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
    ProbeCondition probeCondition = loadFromResource("/test_conditional_02.json");
    class Obj {
      Collection<String> vets = Arrays.asList("vet1", "vet2", "vet3");
    }
    ValueReferenceResolver ctx = RefResolverHelper.createResolver(new Obj());

    // the condition checks if length of vets > 2
    assertTrue(probeCondition.execute(ctx));
  }

  @Test
  void testEquals() throws IOException {
    ProbeCondition probeCondition1 = loadFromResource("/test_conditional_01.json");
    ProbeCondition probeCondition2 = loadFromResource("/test_conditional_01.json");

    assertEquals(probeCondition1, probeCondition1);
    assertEquals(probeCondition1, probeCondition2);
    assertNotEquals(probeCondition1, ProbeCondition.NONE);
    assertNotEquals(probeCondition1, null);
  }

  @Test
  void testHashCode() throws IOException {
    ProbeCondition probeCondition1 = loadFromResource("/test_conditional_01.json");
    ProbeCondition probeCondition2 = loadFromResource("/test_conditional_01.json");

    Map<ProbeCondition, Boolean> map = new HashMap<>();
    map.put(probeCondition1, true);

    assertTrue(map.get(probeCondition2));
    assertNull(map.get(ProbeCondition.NONE));
  }

  @Test
  void testIncorrectSyntax() {
    UnsupportedOperationException ex =
        assertThrows(
            UnsupportedOperationException.class,
            () -> loadFromResource("/test_conditional_03_error.json"));
    assertEquals("Unsupported operation 'gte'", ex.getMessage());
  }

  @Test
  void redaction() throws IOException {
    ProbeCondition probeCondition = loadFromResource("/test_conditional_09.json");
    Map<String, Object> args = new HashMap<>();
    args.put("password", "secret123");
    ValueReferenceResolver ctx = RefResolverHelper.createResolver(args, null);
    EvaluationException evaluationException =
        assertThrows(EvaluationException.class, () -> probeCondition.execute(ctx));
    assertEquals(
        "Could not evaluate the expression because 'password' was redacted",
        evaluationException.getMessage());
  }

  @Test
  void primitives() throws IOException {
    ProbeCondition probeCondition = loadFromResource("/test_conditional_10.json");
    Map<String, Object> args = new HashMap<>();
    args.put("uuid", UUID.fromString("a3cbe9e7-edd3-4bef-8e5b-59bfcb04cf91"));
    args.put("duration", Duration.ofSeconds(42));
    args.put("clazz", "foo".getClass());
    args.put("now", new Date(1700000000000L)); // 2023-11-14T00:00:00Z
    ValueReferenceResolver ctx = RefResolverHelper.createResolver(args, null);
    assertTrue(probeCondition.execute(ctx));
  }

  @Test
  void testBooleanOperation() throws Exception {
    ProbeCondition probeCondition = loadFromResource("/test_conditional_11.json");
    class Obj {
      String strField = "foobar";
      String emptyStr = "";
      List<String> emptyList = new ArrayList<>();
      Map<String, String> emptyMap = new HashMap<>();
      Set<String> emptySet = new HashSet<>();
      Object[] emptyArray = new Object[0];
    }
    ValueReferenceResolver ctx = RefResolverHelper.createResolver(new Obj());
    assertTrue(probeCondition.execute(ctx));
  }

  @Test
  void testLiterals() throws Exception {
    ProbeCondition probeCondition = loadFromResource("/test_conditional_13.json");
    class Obj {
      boolean boolVal = true;
      int intVal = 1;
      long longVal = 1L;
      double doubleVal = 1.0;
      String strVal = "foo";
      Object objVal = null;
      char charVal = 'a';
    }
    ValueReferenceResolver ctx = RefResolverHelper.createResolver(new Obj());
    assertTrue(probeCondition.execute(ctx));
  }

  @Test
  void testLenCount() throws Exception {
    ProbeCondition probeCondition = loadFromResource("/test_conditional_14.json");
    class Obj {
      int[] intArray = new int[] {1, 1, 1};
      String[] strArray = new String[] {"foo", "bar"};
      Map<String, String> strMap = new HashMap<>();

      {
        strMap.put("foo", "bar");
        strMap.put("bar", "foobar");
      }

      Set<String> strSet = new HashSet<>();

      {
        strSet.add("foo");
      }

      List<String> strList = new ArrayList<>();

      {
        strList.add("foo");
      }
    }
    ValueReferenceResolver ctx = RefResolverHelper.createResolver(new Obj());
    assertTrue(probeCondition.execute(ctx));
  }

  @Test
  public void nullExpressions() {
    class Obj {
      Object field = null;
    }
    List<String> lines = loadLinesFromResource("/null_expressions.txt");
    for (String line : lines) {
      ValueReferenceResolver ctx = RefResolverHelper.createResolver(new Obj());
      EvaluationException ex =
          assertThrows(EvaluationException.class, () -> load(line).execute(ctx));
      assertEquals("Cannot evaluate the expression for null value", ex.getMessage(), line);
    }
  }

  @Test
  public void containsExpressions() {
    class Obj {
      String str = "hello";
      String[] arrayStr = new String[] {"foo", "hello", "bar"};
      Map<String, String> mapStr = new HashMap<>();

      {
        mapStr.put("foo", "bar");
        mapStr.put("hello", "bar");
      }
    }
    List<String> lines = loadLinesFromResource("/contains_expressions.txt");
    for (String line : lines) {
      ValueReferenceResolver ctx = RefResolverHelper.createResolver(new Obj());
      assertTrue(load(line).execute(ctx));
    }
  }

  private static ProbeCondition loadFromResource(String resourcePath) throws IOException {
    InputStream input = ProbeConditionTest.class.getResourceAsStream(resourcePath);
    Moshi moshi =
        new Moshi.Builder()
            .add(ProbeCondition.class, new ProbeCondition.ProbeConditionJsonAdapter())
            .build();
    return moshi.adapter(ProbeCondition.class).fromJson(Okio.buffer(Okio.source(input)));
  }

  private static List<String> loadLinesFromResource(String resourcePath) {
    try (InputStream input = ProbeConditionTest.class.getResourceAsStream(resourcePath)) {
      BufferedReader reader = new BufferedReader(new InputStreamReader(input));
      return reader.lines().collect(Collectors.toList());
    } catch (IOException e) {
      throw new RuntimeException("Failed to load resource: " + resourcePath, e);
    }
  }

  private static ProbeCondition load(String json) {
    try {
      Moshi moshi =
          new Moshi.Builder()
              .add(ProbeCondition.class, new ProbeCondition.ProbeConditionJsonAdapter())
              .build();
      return moshi.adapter(ProbeCondition.class).fromJson(json);
    } catch (IOException e) {
      throw new RuntimeException("Failed to load json: " + json, e);
    }
  }

  static class Container {
    String msg;

    public Container(String msg) {
      this.msg = msg;
    }
  }
}
