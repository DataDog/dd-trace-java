package com.datadog.debugger.probe;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datadog.debugger.util.MoshiHelper;
import com.squareup.moshi.JsonAdapter;
import java.io.IOException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class WhereTest {
  @Test
  public void simpleLineRange() {
    Where where =
        new Where(
            "java.lang.Object", "toString()", "java.lang.String ()", new String[] {"5-7"}, null);
    assertTrue(where.isSignatureMatching("java.lang.String ()"));
    String[] lines = where.getLines();
    Assertions.assertNotNull(lines);
    Assertions.assertEquals(1, lines.length);
    Assertions.assertEquals("5-7", lines[0]);
  }

  @Test
  public void multiLines() {
    Where where =
        new Where(
            "java.lang.Object",
            "toString()",
            "java.lang.String ()",
            new String[] {"12-25", "42-45"},
            null);
    assertTrue(where.isSignatureMatching("java.lang.String ()"));
    String[] lines = where.getLines();
    Assertions.assertNotNull(lines);
    Assertions.assertEquals(2, lines.length);
    Assertions.assertEquals("12-25", lines[0]);
    Assertions.assertEquals("42-45", lines[1]);
  }

  @Test
  public void singleLine() {
    Where where =
        new Where(
            "java.lang.Object", "toString()", "java.lang.String ()", new String[] {"12"}, null);
    assertTrue(where.isSignatureMatching("java.lang.String ()"));
    String[] lines = where.getLines();
    Assertions.assertNotNull(lines);
    Assertions.assertEquals(1, lines.length);
    Assertions.assertEquals("12", lines[0]);
  }

  @Test
  public void noLines() {
    Where where =
        new Where(
            "java.lang.Object",
            "toString()",
            "java.lang.String ()",
            (Where.SourceLine[]) null,
            null);
    assertTrue(where.isSignatureMatching("java.lang.String ()"));
    String[] lines = where.getLines();
    Assertions.assertNull(lines);
  }

  @Test
  public void linesRoundTrip() throws IOException {
    JsonAdapter<Where.SourceLine[]> adapter =
        MoshiHelper.createMoshiConfig().adapter(Where.SourceLine[].class);

    String linesJson = "[\"12\",\"40-42\"]";
    Where.SourceLine[] lines = adapter.fromJson(linesJson);
    Assertions.assertEquals(2, lines.length);
    Assertions.assertEquals(new Where.SourceLine(12), lines[0]);
    Assertions.assertEquals(new Where.SourceLine(40, 42), lines[1]);
    Assertions.assertEquals(linesJson, adapter.toJson(lines));
  }

  @Test
  public void methodMatching() {
    Where where = new Where("String", "substring", "(int,int)", new String[0], null);
    assertTrue(where.isMethodMatching("substring", "(II)Ljava/lang/String;"));
    where = new Where("String", "replaceAll", "(String,String)", new String[0], null);
    assertTrue(
        where.isMethodMatching(
            "replaceAll", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"));
    where = new Where("HashMap", "<init>", "(Map)", new String[0], null);
    assertTrue(where.isMethodMatching("<init>", "(Ljava/util/Map;)V"));
    where = new Where("ArrayList", "removeIf", "(Predicate)", new String[0], null);
    assertTrue(where.isMethodMatching("removeIf", "(Ljava/util/function/Predicate;)Z"));
    where = new Where("String", "concat", "", new String[0], null);
    assertTrue(where.isMethodMatching("concat", "String (String)"));
    where = new Where("String", "concat", " \t", new String[0], null);
    assertTrue(where.isMethodMatching("concat", "String (String)"));
    where =
        new Where(
            "Inner",
            "innerMethod",
            "(com.datadog.debugger.probe.Outer$Inner)",
            new String[0],
            null);
    assertTrue(
        where.isMethodMatching("innerMethod", "(Lcom/datadog/debugger/probe/Outer$Inner;)V"));
    where = new Where("Inner", "innerMethod", "(Outer$Inner)", new String[0], null);
    assertTrue(
        where.isMethodMatching("innerMethod", "(Lcom/datadog/debugger/probe/Outer$Inner;)V"));
  }
}
