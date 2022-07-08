package com.datadog.debugger.agent;

import org.junit.Assert;
import org.junit.jupiter.api.Test;

public class WhereTest {
  @Test
  public void simpleLineRange() {
    Where where =
        new Where(
            "java.lang.Object", "toString()", "java.lang.String ()", new String[] {"5-7"}, null);
    Assert.assertTrue(where.isSignatureMatching("java.lang.String ()"));
    String[] lines = where.getLines();
    Assert.assertNotNull(lines);
    Assert.assertEquals(1, lines.length);
    Assert.assertEquals("5-7", lines[0]);
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
    Assert.assertTrue(where.isSignatureMatching("java.lang.String ()"));
    String[] lines = where.getLines();
    Assert.assertNotNull(lines);
    Assert.assertEquals(2, lines.length);
    Assert.assertEquals("12-25", lines[0]);
    Assert.assertEquals("42-45", lines[1]);
  }

  @Test
  public void methodMatching() {
    Where where = new Where("String", "substring", "(int,int)", new String[0], null);
    Assert.assertTrue(where.isMethodMatching("substring", "(II)Ljava/lang/String;"));
    where = new Where("String", "replaceAll", "(String,String)", new String[0], null);
    Assert.assertTrue(
        where.isMethodMatching(
            "replaceAll", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"));
    where = new Where("HashMap", "<init>", "(Map)", new String[0], null);
    Assert.assertTrue(where.isMethodMatching("<init>", "(Ljava/util/Map;)V"));
    where = new Where("ArrayList", "removeIf", "(Predicate)", new String[0], null);
    Assert.assertTrue(where.isMethodMatching("removeIf", "(Ljava/util/function/Predicate;)Z"));
    where = new Where("String", "concat", "", new String[0], null);
    Assert.assertTrue(where.isMethodMatching("concat", "String (String)"));
    where = new Where("String", "concat", " \t", new String[0], null);
    Assert.assertTrue(where.isMethodMatching("concat", "String (String)"));
  }
}
