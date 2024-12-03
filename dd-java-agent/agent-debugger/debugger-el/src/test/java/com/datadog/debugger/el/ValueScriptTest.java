package com.datadog.debugger.el;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.squareup.moshi.Moshi;
import datadog.trace.bootstrap.debugger.el.Values;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import okio.Okio;
import org.junit.jupiter.api.Test;

public class ValueScriptTest {

  static class Obj {
    String str = "hello";
    int i = 10;
    List<String> list = Arrays.asList("a", "b", "c");
    long l = 100_000_000_000L;
    float f = 2.5F;
    double d = 3.14D;
    char c = 'a';
  }

  @Test
  public void predicates() {
    ValueScript valueScript = loadFromResource("/test_value_expr_01.json");
    assertEquals(
        Boolean.TRUE, valueScript.execute(RefResolverHelper.createResolver(new Obj())).getValue());
  }

  @Test
  public void topLevelPredicates() {
    List<String> lines = loadLinesFromResource("/test_one_liner_value_expr_01.txt");
    for (String line : lines) {
      ValueScript valueScript = load(line);
      assertEquals(
          Boolean.TRUE,
          valueScript.execute(RefResolverHelper.createResolver(new Obj())).getValue(),
          line);
    }
  }

  @Test
  public void topLevelPrimitives() {
    Object[] expectedValues =
        new Object[] {
          "hello",
          "hello",
          10,
          100_000_000_000L,
          2.5F,
          3.14D,
          "a",
          "b",
          5,
          3,
          "el",
          Values.NULL_OBJECT,
          Boolean.TRUE,
          Boolean.FALSE,
          42,
          Integer.MAX_VALUE,
          -42,
          Integer.MIN_VALUE,
          17315993717L,
          -17315993717L,
          3.14
        };
    List<String> lines = loadLinesFromResource("/test_one_liner_value_expr_02.txt");
    int i = 0;
    for (String line : lines) {
      ValueScript valueScript = load(line);
      assertEquals(
          expectedValues[i],
          valueScript.execute(RefResolverHelper.createResolver(new Obj())).getValue(),
          line);
      i++;
    }
  }

  private static List<String> loadLinesFromResource(String resourcePath) {
    try (InputStream input = ProbeConditionTest.class.getResourceAsStream(resourcePath)) {
      BufferedReader reader = new BufferedReader(new InputStreamReader(input));
      return reader.lines().collect(Collectors.toList());
    } catch (IOException e) {
      throw new RuntimeException("Failed to load resource: " + resourcePath, e);
    }
  }

  private static ValueScript loadFromResource(String resourcePath) {
    try (InputStream input = ProbeConditionTest.class.getResourceAsStream(resourcePath)) {
      Moshi moshi =
          new Moshi.Builder().add(ValueScript.class, new ValueScript.ValueScriptAdapter()).build();
      return moshi.adapter(ValueScript.class).fromJson(Okio.buffer(Okio.source(input)));
    } catch (IOException e) {
      throw new RuntimeException("Failed to load resource: " + resourcePath, e);
    }
  }

  private static ValueScript load(String json) {
    try {
      Moshi moshi =
          new Moshi.Builder().add(ValueScript.class, new ValueScript.ValueScriptAdapter()).build();
      return moshi.adapter(ValueScript.class).fromJson(json);
    } catch (IOException e) {
      throw new RuntimeException("Failed to load json: " + json, e);
    }
  }
}
