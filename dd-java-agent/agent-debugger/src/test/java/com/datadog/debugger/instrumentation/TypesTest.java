package com.datadog.debugger.instrumentation;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.AALOAD;
import static org.objectweb.asm.Opcodes.AASTORE;
import static org.objectweb.asm.Opcodes.BALOAD;
import static org.objectweb.asm.Opcodes.BASTORE;
import static org.objectweb.asm.Opcodes.CALOAD;
import static org.objectweb.asm.Opcodes.CASTORE;
import static org.objectweb.asm.Opcodes.DALOAD;
import static org.objectweb.asm.Opcodes.DASTORE;
import static org.objectweb.asm.Opcodes.FALOAD;
import static org.objectweb.asm.Opcodes.FASTORE;
import static org.objectweb.asm.Opcodes.IALOAD;
import static org.objectweb.asm.Opcodes.IASTORE;
import static org.objectweb.asm.Opcodes.LALOAD;
import static org.objectweb.asm.Opcodes.LASTORE;
import static org.objectweb.asm.Opcodes.SALOAD;
import static org.objectweb.asm.Opcodes.SASTORE;

import java.util.stream.Stream;
import org.junit.Assert;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

class TypesTest {
  @ParameterizedTest
  @ValueSource(classes = {int.class, byte.class, char.class, short.class, String.class})
  void testFromClassName(Class<?> clazz) {
    assertEquals(Type.getType(clazz), Types.fromClassName(clazz.getName()));

    assertEquals(
        Types.asArray(Type.getType(clazz), 1), Types.fromClassName(clazz.getName() + "[]"));
  }

  @ParameterizedTest
  @ValueSource(classes = {int[].class, int[][].class, String[].class, String[][].class})
  void testAsArray(Class<?> clazz) {
    Type t = Type.getType(clazz);

    assertEquals(t, Types.asArray(t.getElementType(), t.getDimensions()));
  }

  @Test
  void testDescriptorFromSignatureWithArgs() {
    String expectedDesc = "(I[JLjava/util/Map;[[Ljava/lang/String;)Ljava/lang/String;";
    String signature = "java.lang.String (int, long[], java.util.Map, java.lang.String[][])";
    String desc = Types.descriptorFromSignature(signature);
    assertEquals(expectedDesc, desc);
  }

  @Test
  void testDescriptorFromSignatureNoArgs() {
    String expectedDesc = "()Ljava/lang/String;";
    String signature = "java.lang.String ()";
    String desc = Types.descriptorFromSignature(signature);
    assertEquals(expectedDesc, desc);
  }

  @Test
  void testDescriptorFromSignatureNoArgsVoid() {
    String expectedDesc = "()V";
    String signature = "void   ( )";
    String desc = Types.descriptorFromSignature(signature);
    assertEquals(expectedDesc, desc);
  }

  @Test
  void testDescriptorFromSignatureInvalid() {
    assertThrows(IllegalArgumentException.class, () -> Types.descriptorFromSignature("()"));
    assertThrows(IllegalArgumentException.class, () -> Types.descriptorFromSignature("int"));
    assertThrows(IllegalArgumentException.class, () -> Types.descriptorFromSignature("int ("));
    assertThrows(IllegalArgumentException.class, () -> Types.descriptorFromSignature("int ("));
    assertThrows(IllegalArgumentException.class, () -> Types.descriptorFromSignature("int (, )"));
    assertThrows(
        IllegalArgumentException.class, () -> Types.descriptorFromSignature("int (a, ,b)"));
  }

  @ParameterizedTest
  @MethodSource("provideGetArrayType")
  void testGetArrayType(Class<?> clazz, int opcode) {
    Assert.assertEquals(Type.getType(clazz), Types.getArrayType(opcode));
  }

  private static Stream<Arguments> provideGetArrayType() {
    return Stream.of(
        Arguments.of(int[].class, IALOAD),
        Arguments.of(int[].class, IASTORE),
        Arguments.of(byte[].class, BALOAD),
        Arguments.of(byte[].class, BASTORE),
        Arguments.of(Object[].class, AALOAD),
        Arguments.of(Object[].class, AASTORE),
        Arguments.of(char[].class, CALOAD),
        Arguments.of(char[].class, CASTORE),
        Arguments.of(float[].class, FALOAD),
        Arguments.of(float[].class, FASTORE),
        Arguments.of(short[].class, SALOAD),
        Arguments.of(short[].class, SASTORE),
        Arguments.of(long[].class, LALOAD),
        Arguments.of(long[].class, LASTORE),
        Arguments.of(double[].class, DALOAD),
        Arguments.of(double[].class, DASTORE));
  }

  @ParameterizedTest
  @MethodSource("provideGetElementType")
  void testGetElementType(Class<?> clazz, int opcode) {
    Assert.assertEquals(Type.getType(clazz), Types.getElementType(opcode));
  }

  private static Stream<Arguments> provideGetElementType() {
    return Stream.of(
        Arguments.of(int.class, IALOAD),
        Arguments.of(int.class, IASTORE),
        Arguments.of(byte.class, BALOAD),
        Arguments.of(byte.class, BASTORE),
        Arguments.of(Object.class, AALOAD),
        Arguments.of(Object.class, AASTORE),
        Arguments.of(char.class, CALOAD),
        Arguments.of(char.class, CASTORE),
        Arguments.of(float.class, FALOAD),
        Arguments.of(float.class, FASTORE),
        Arguments.of(short.class, SALOAD),
        Arguments.of(short.class, SASTORE),
        Arguments.of(long.class, LALOAD),
        Arguments.of(long.class, LASTORE),
        Arguments.of(double.class, DALOAD),
        Arguments.of(double.class, DASTORE));
  }

  @ParameterizedTest
  @MethodSource("provideGetFrameItemType")
  void testGetFrameItemType(Class<?> clazz, Object opcode) {
    Assert.assertEquals(Type.getType(clazz), Types.getFrameItemType(opcode));
  }

  private static Stream<Arguments> provideGetFrameItemType() {
    return Stream.of(
        Arguments.of(int.class, Opcodes.T_INT),
        Arguments.of(byte.class, Opcodes.T_BYTE),
        Arguments.of(char.class, Opcodes.T_CHAR),
        Arguments.of(float.class, Opcodes.T_FLOAT),
        Arguments.of(short.class, Opcodes.T_SHORT),
        Arguments.of(long.class, Opcodes.T_LONG),
        Arguments.of(double.class, Opcodes.T_DOUBLE),
        Arguments.of(String.class, "Ljava/lang/String;"));
  }
}
