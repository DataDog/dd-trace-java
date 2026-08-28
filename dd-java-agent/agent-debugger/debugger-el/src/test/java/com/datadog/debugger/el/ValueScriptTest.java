package com.datadog.debugger.el;

import static com.datadog.debugger.el.EvalContextHelper.createResolver;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.squareup.moshi.Moshi;
import datadog.trace.api.Config;
import datadog.trace.bootstrap.debugger.el.Values;
import datadog.trace.bootstrap.debugger.util.TimeoutChecker;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import okio.Okio;
import org.junit.jupiter.api.Test;

public class ValueScriptTest {
  private static final Duration TEST_TIMEOUT = Duration.ofMinutes(500);

  static class Obj {
    String str = "hello";
    int i = 10;
    List<String> list = Arrays.asList("a", "b", "c");
    List<String> largeList = new ArrayList<>();
    long l = 100_000_000_000L;
    float f = 2.5F;
    double d = 3.14D;
    char c = 'a';

    {
      for (int i = 0; i < 5_000; i++) {
        largeList.add("hello" + i);
      }
    }
  }

  @Test
  public void predicates() {
    ValueScript valueScript = loadFromResource("/test_value_expr_01.json");
    // first call is longer so ideal to test timeout
    EvaluationException evaluationException =
        assertThrows(
            EvaluationException.class,
            () ->
                valueScript.execute(
                    createResolver(new Obj()),
                    TimeoutChecker.create(Config.get(), Duration.ofMillis(1))));
    assertEquals("timeout (1ms)", evaluationException.getMessage());
    // test good execution
    assertEquals(
        Boolean.TRUE,
        valueScript
            .execute(createResolver(new Obj()), TimeoutChecker.create(Config.get(), TEST_TIMEOUT))
            .getValue());
  }

  @Test
  public void topLevelPredicates() {
    List<String> lines = loadLinesFromResource("/test_one_liner_value_expr_01.txt");
    for (String line : lines) {
      ValueScript valueScript = load(line);
      assertEquals(
          Boolean.TRUE,
          valueScript
              .execute(createResolver(new Obj()), TimeoutChecker.create(Config.get(), TEST_TIMEOUT))
              .getValue(),
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
          valueScript
              .execute(createResolver(new Obj()), TimeoutChecker.create(Config.get(), TEST_TIMEOUT))
              .getValue(),
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
