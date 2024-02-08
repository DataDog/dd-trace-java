package com.datadog.debugger.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static utils.InstrumentationTestHelper.compile;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodNode;

class ClassFileLinesTest {
  @Test
  void isEmptyReturnTrue() {
    ClassFileLines classFileLines = new ClassFileLines(new ClassNode());
    assertTrue(classFileLines.isEmpty());
    assertNull(classFileLines.getLineLabel(2));
  }

  @Test
  void ValidateLineMapReturnLinesInRangeOrNull() throws Exception {
    ClassFileLines classFileLines = createClassFileLines();
    LabelNode line13 = classFileLines.getLineLabel(13);
    LabelNode line17 = classFileLines.getLineLabel(17);

    assertEquals(line13, classFileLines.getLineLabel(0));
    assertEquals(line17, classFileLines.getLineLabel(15));
    assertNull(classFileLines.getLineLabel(70));
  }

  @Test
  void getMethodByLine() throws Exception {
    ClassFileLines classFileLines = createClassFileLines();
    MethodNode method13 = classFileLines.getMethodByLine(13);
    assertEquals("<init>", method13.name);
    assertEquals("()V", method13.desc);
    MethodNode method17 = classFileLines.getMethodByLine(17);
    assertEquals("<init>", method17.name);
    assertEquals("(Ljava/lang/Throwable;)V", method17.desc);
    MethodNode method26 = classFileLines.getMethodByLine(26);
    assertEquals("<init>", method26.name);
    assertEquals("(Ljava/lang/String;Ljava/lang/Object;)V", method26.desc);
    MethodNode method32 = classFileLines.getMethodByLine(32);
    assertEquals("createObject", method32.name);
    assertEquals("()Ljava/lang/Object;", method32.desc);
    MethodNode method37 = classFileLines.getMethodByLine(37);
    assertEquals("f", method37.name);
    assertEquals("()V", method37.desc);
    MethodNode method44 = classFileLines.getMethodByLine(44);
    assertEquals("synchronizedBlock", method44.name);
    assertEquals("(I)I", method44.desc);
    MethodNode method54 = classFileLines.getMethodByLine(54);
    assertEquals("main", method54.name);
    assertEquals("(Ljava/lang/String;)I", method54.desc);
  }

  private ClassFileLines createClassFileLines() throws Exception {
    final String CLASS_NAME = "CapturedSnapshot02";
    byte[] byteBuffer = compile(CLASS_NAME).get(CLASS_NAME);
    ClassReader reader = new ClassReader(byteBuffer);
    ClassNode classNode = new ClassNode();
    reader.accept(classNode, ClassReader.SKIP_FRAMES);
    return new ClassFileLines(classNode);
  }
}
