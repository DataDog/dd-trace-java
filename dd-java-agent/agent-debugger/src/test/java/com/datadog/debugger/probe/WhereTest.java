package com.datadog.debugger.probe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.datadog.debugger.util.ClassFileLines;
import com.datadog.debugger.util.MoshiHelper;
import com.squareup.moshi.JsonAdapter;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.MethodNode;

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
  public void convertLineToMethod() {
    Where wherePut = Where.of("java.util.Map", "put", "(Object, Object)", "42");
    ClassFileLines classFileLines = mock(ClassFileLines.class);
    MethodNode methodNode = createMethodNode("put", "(Ljava/lang/Object;Ljava/lang/Object;)V");
    when(classFileLines.getMethodsByLine(42)).thenReturn(Collections.singletonList(methodNode));
    Where whereMapPut = Where.convertLineToMethod(wherePut, classFileLines);
    assertEquals("java.util.Map", whereMapPut.getTypeName());
    assertEquals("put", whereMapPut.getMethodName());
    assertEquals("(java.lang.Object, java.lang.Object)", whereMapPut.getSignature());
    assertNull(whereMapPut.getLines());
  }

  @Test
  public void methodMatching() {
    Where where = new Where("String", "substring", "(int,int)", new String[0], null);
    assertEquals(
        Where.MethodMatching.MATCH,
        where.isMethodMatching(createMethodNode("substring", "(II)Ljava/lang/String;"), null));
    where = new Where("String", "replaceAll", "(String,String)", new String[0], null);
    assertEquals(
        Where.MethodMatching.MATCH,
        where.isMethodMatching(
            createMethodNode(
                "replaceAll", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"),
            null));
    where = new Where("HashMap", "<init>", "(Map)", new String[0], null);
    assertEquals(
        Where.MethodMatching.MATCH,
        where.isMethodMatching(createMethodNode("<init>", "(Ljava/util/Map;)V"), null));
    where = new Where("ArrayList", "removeIf", "(Predicate)", new String[0], null);
    assertEquals(
        Where.MethodMatching.MATCH,
        where.isMethodMatching(
            createMethodNode("removeIf", "(Ljava/util/function/Predicate;)Z"), null));
    where = new Where("String", "concat", "", new String[0], null);
    assertEquals(
        Where.MethodMatching.MATCH,
        where.isMethodMatching(createMethodNode("concat", "String (String)"), null));
    where = new Where("String", "concat", " \t", new String[0], null);
    assertEquals(
        Where.MethodMatching.MATCH,
        where.isMethodMatching(createMethodNode("concat", "String (String)"), null));
    where =
        new Where(
            "Inner",
            "innerMethod",
            "(com.datadog.debugger.probe.Outer$Inner)",
            new String[0],
            null);
    assertEquals(
        Where.MethodMatching.MATCH,
        where.isMethodMatching(
            createMethodNode("innerMethod", "(Lcom/datadog/debugger/probe/Outer$Inner;)V"), null));
    where = new Where("Inner", "innerMethod", "(Outer$Inner)", new String[0], null);
    assertEquals(
        Where.MethodMatching.MATCH,
        where.isMethodMatching(
            createMethodNode("innerMethod", "(Lcom/datadog/debugger/probe/Outer$Inner;)V"), null));
    where = new Where("MyClass", "myMethod", null, new String[] {"42"}, null);
    ClassFileLines classFileLines = mock(ClassFileLines.class);
    MethodNode myMethodNode = createMethodNode("myMethod", "()V");
    when(classFileLines.getMethodsByLine(42)).thenReturn(Arrays.asList(myMethodNode));
    assertEquals(Where.MethodMatching.MATCH, where.isMethodMatching(myMethodNode, classFileLines));
  }

  private MethodNode createMethodNode(String name, String desc) {
    MethodNode methodNode = new MethodNode();
    methodNode.name = name;
    methodNode.desc = desc;
    return methodNode;
  }
}
