package com.datadog.debugger.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static utils.InstrumentationTestHelper.compile;

import java.util.List;
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
    ClassFileLines classFileLines = createClassFileLines("CapturedSnapshot02");
    LabelNode line13 = classFileLines.getLineLabel(13);
    LabelNode line17 = classFileLines.getLineLabel(17);

    assertEquals(line13, classFileLines.getLineLabel(0));
    assertEquals(line17, classFileLines.getLineLabel(15));
    assertNull(classFileLines.getLineLabel(70));
  }

  @Test
  void getMethodsByLine() throws Exception {
    ClassFileLines classFileLines = createClassFileLines("CapturedSnapshot02");
    MethodNode method13 = classFileLines.getMethodsByLine(13).get(0);
    assertEquals("<init>", method13.name);
    assertEquals("()V", method13.desc);
    MethodNode method17 = classFileLines.getMethodsByLine(17).get(0);
    assertEquals("<init>", method17.name);
    assertEquals("(Ljava/lang/Throwable;)V", method17.desc);
    MethodNode method26 = classFileLines.getMethodsByLine(26).get(0);
    assertEquals("<init>", method26.name);
    assertEquals("(Ljava/lang/String;Ljava/lang/Object;)V", method26.desc);
    MethodNode method32 = classFileLines.getMethodsByLine(32).get(0);
    assertEquals("createObject", method32.name);
    assertEquals("()Ljava/lang/Object;", method32.desc);
    MethodNode method37 = classFileLines.getMethodsByLine(37).get(0);
    assertEquals("f", method37.name);
    assertEquals("()V", method37.desc);
    MethodNode method44 = classFileLines.getMethodsByLine(44).get(0);
    assertEquals("synchronizedBlock", method44.name);
    assertEquals("(I)I", method44.desc);
    MethodNode method54 = classFileLines.getMethodsByLine(54).get(0);
    assertEquals("main", method54.name);
    assertEquals("(Ljava/lang/String;)I", method54.desc);
  }

  @Test
  void getMethodsByLineWithLambdas() throws Exception {
    ClassFileLines classFileLines = createClassFileLines("CapturedSnapshot07");
    List<MethodNode> methods57 = classFileLines.getMethodsByLine(57);
    // despite having the return on multi line which yield multiple BCI for same line
    // this is still the same method
    assertEquals(1, methods57.size());
    assertEquals("multiLambda", methods57.get(0).name);
    assertEquals("(Ljava/lang/String;)I", methods57.get(0).desc);
    List<MethodNode> methods58 = classFileLines.getMethodsByLine(58);
    assertEquals(3, methods58.size());
    assertEquals("multiLambda", methods58.get(0).name);
    assertEquals("(Ljava/lang/String;)I", methods58.get(0).desc);
    // filter
    assertTrue(methods58.get(1).name.startsWith("lambda$multiLambda$"));
    assertEquals("(Ljava/lang/String;)Z", methods58.get(1).desc);
    // map
    assertTrue(methods58.get(2).name.startsWith("lambda$multiLambda$"));
    assertEquals("(Ljava/lang/String;)Ljava/lang/String;", methods58.get(2).desc);
  }

  private ClassFileLines createClassFileLines(String className) throws Exception {
    byte[] byteBuffer = compile(className).get(className);
    ClassReader reader = new ClassReader(byteBuffer);
    ClassNode classNode = new ClassNode();
    reader.accept(classNode, ClassReader.SKIP_FRAMES);
    return new ClassFileLines(classNode);
  }
}
