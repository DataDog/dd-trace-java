package datadog.trace.plugin.csi.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import datadog.trace.plugin.csi.util.MethodType;
import org.junit.jupiter.api.Test;

class RegexpAdvicePointcutParserTest {

  @Test
  void resolveConstructor() {
    RegexpAdvicePointcutParser pointcutParser = new RegexpAdvicePointcutParser();

    MethodType signature =
        pointcutParser.parse(
            "void datadog.trace.plugin.csi.samples.SignatureParserExample.<init>()");

    assertEquals(
        "datadog.trace.plugin.csi.samples.SignatureParserExample",
        signature.getOwner().getClassName());
    assertEquals("<init>", signature.getMethodName());
    assertEquals("()V", signature.getMethodType().getDescriptor());
  }

  @Test
  void resolveConstructorWithArgs() {
    RegexpAdvicePointcutParser pointcutParser = new RegexpAdvicePointcutParser();

    MethodType signature =
        pointcutParser.parse(
            "void datadog.trace.plugin.csi.samples.SignatureParserExample.<init>(java.lang.String)");

    assertEquals(
        "datadog.trace.plugin.csi.samples.SignatureParserExample",
        signature.getOwner().getClassName());
    assertEquals("<init>", signature.getMethodName());
    assertEquals("(Ljava/lang/String;)V", signature.getMethodType().getDescriptor());
  }

  @Test
  void resolveWithoutArgs() {
    RegexpAdvicePointcutParser pointcutParser = new RegexpAdvicePointcutParser();

    MethodType signature =
        pointcutParser.parse(
            "java.lang.String datadog.trace.plugin.csi.samples.SignatureParserExample.noParams()");

    assertEquals(
        "datadog.trace.plugin.csi.samples.SignatureParserExample",
        signature.getOwner().getClassName());
    assertEquals("noParams", signature.getMethodName());
    assertEquals("()Ljava/lang/String;", signature.getMethodType().getDescriptor());
  }

  @Test
  void resolveOneParam() {
    RegexpAdvicePointcutParser pointcutParser = new RegexpAdvicePointcutParser();

    MethodType signature =
        pointcutParser.parse(
            "java.lang.String datadog.trace.plugin.csi.samples.SignatureParserExample.oneParam(java.util.Map)");

    assertEquals(
        "datadog.trace.plugin.csi.samples.SignatureParserExample",
        signature.getOwner().getClassName());
    assertEquals("oneParam", signature.getMethodName());
    assertEquals("(Ljava/util/Map;)Ljava/lang/String;", signature.getMethodType().getDescriptor());
  }

  @Test
  void resolveMultipleParams() {
    RegexpAdvicePointcutParser pointcutParser = new RegexpAdvicePointcutParser();

    MethodType signature =
        pointcutParser.parse(
            "java.lang.String datadog.trace.plugin.csi.samples.SignatureParserExample.multipleParams(java.lang.String, int, java.util.List)");

    assertEquals(
        "datadog.trace.plugin.csi.samples.SignatureParserExample",
        signature.getOwner().getClassName());
    assertEquals("multipleParams", signature.getMethodName());
    assertEquals(
        "(Ljava/lang/String;ILjava/util/List;)Ljava/lang/String;",
        signature.getMethodType().getDescriptor());
  }

  @Test
  void resolveVarargs() {
    RegexpAdvicePointcutParser pointcutParser = new RegexpAdvicePointcutParser();

    MethodType signature =
        pointcutParser.parse(
            "java.lang.String datadog.trace.plugin.csi.samples.SignatureParserExample.varargs(java.lang.String[])");

    assertEquals(
        "datadog.trace.plugin.csi.samples.SignatureParserExample",
        signature.getOwner().getClassName());
    assertEquals("varargs", signature.getMethodName());
    assertEquals(
        "([Ljava/lang/String;)Ljava/lang/String;", signature.getMethodType().getDescriptor());
  }

  @Test
  void resolvePrimitive() {
    RegexpAdvicePointcutParser pointcutParser = new RegexpAdvicePointcutParser();

    MethodType signature =
        pointcutParser.parse(
            "int datadog.trace.plugin.csi.samples.SignatureParserExample.primitive()");

    assertEquals(
        "datadog.trace.plugin.csi.samples.SignatureParserExample",
        signature.getOwner().getClassName());
    assertEquals("primitive", signature.getMethodName());
    assertEquals("()I", signature.getMethodType().getDescriptor());
  }

  @Test
  void resolvePrimitiveArrayType() {
    RegexpAdvicePointcutParser pointcutParser = new RegexpAdvicePointcutParser();

    MethodType signature =
        pointcutParser.parse(
            "byte[] datadog.trace.plugin.csi.samples.SignatureParserExample.primitiveArray()");

    assertEquals(
        "datadog.trace.plugin.csi.samples.SignatureParserExample",
        signature.getOwner().getClassName());
    assertEquals("primitiveArray", signature.getMethodName());
    assertEquals("()[B", signature.getMethodType().getDescriptor());
  }

  @Test
  void resolveObjectArrayType() {
    RegexpAdvicePointcutParser pointcutParser = new RegexpAdvicePointcutParser();

    MethodType signature =
        pointcutParser.parse(
            "java.lang.Object[] datadog.trace.plugin.csi.samples.SignatureParserExample.objectArray()");

    assertEquals(
        "datadog.trace.plugin.csi.samples.SignatureParserExample",
        signature.getOwner().getClassName());
    assertEquals("objectArray", signature.getMethodName());
    assertEquals("()[Ljava/lang/Object;", signature.getMethodType().getDescriptor());
  }

  @Test
  void resolveMultiDimensionalObjectArrayType() {
    RegexpAdvicePointcutParser pointcutParser = new RegexpAdvicePointcutParser();

    MethodType signature =
        pointcutParser.parse(
            "java.lang.Object[][][] datadog.trace.plugin.csi.samples.SignatureParserExample.objectArray()");

    assertEquals(
        "datadog.trace.plugin.csi.samples.SignatureParserExample",
        signature.getOwner().getClassName());
    assertEquals("objectArray", signature.getMethodName());
    assertEquals("()[[[Ljava/lang/Object;", signature.getMethodType().getDescriptor());
  }
}
