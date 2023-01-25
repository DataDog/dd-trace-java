package com.datadog.debugger.agent;

import static com.datadog.debugger.util.ClassFileHelperTest.getClassFileBytes;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.instrument.IllegalClassFormatException;
import java.util.List;
import org.junit.jupiter.api.Test;

class SourceFileTrackingTransformerTest {
  @Test
  void transformTopLevel() throws IllegalClassFormatException {
    SourceFileTrackingTransformer sourceFileTrackingTransformer =
        new SourceFileTrackingTransformer();
    sourceFileTrackingTransformer.transform(
        null,
        getInternalName(TopLevelHelper.class),
        null,
        null,
        getClassFileBytes(TopLevelHelper.class));
    assertNull(sourceFileTrackingTransformer.getClassNameBySourceFile("TopLevelHelper.java"));
    sourceFileTrackingTransformer.transform(
        null,
        getInternalName(MyTopLevelClass.class),
        null,
        null,
        getClassFileBytes(MyTopLevelClass.class));
    List<String> classNameBySourceFile =
        sourceFileTrackingTransformer.getClassNameBySourceFile("TopLevelHelper.java");
    assertEquals(1, classNameBySourceFile.size());
    assertEquals(getInternalName(MyTopLevelClass.class), classNameBySourceFile.get(0));
  }

  @Test
  void transformInner() throws IllegalClassFormatException {
    SourceFileTrackingTransformer sourceFileTrackingTransformer =
        new SourceFileTrackingTransformer();
    sourceFileTrackingTransformer.transform(
        null, getInternalName(InnerHelper.class), null, null, getClassFileBytes(InnerHelper.class));
    assertNull(sourceFileTrackingTransformer.getClassNameBySourceFile("TopLevelHelper.java"));
    sourceFileTrackingTransformer.transform(
        null,
        getInternalName(InnerHelper.MyInner.class),
        null,
        null,
        getClassFileBytes(InnerHelper.MyInner.class));
    List<String> classNameBySourceFile =
        sourceFileTrackingTransformer.getClassNameBySourceFile("InnerHelper.java");
    assertEquals(1, classNameBySourceFile.size());
    assertEquals(getInternalName(InnerHelper.MyInner.class), classNameBySourceFile.get(0));
  }

  private String getInternalName(Class<?> clazz) {
    return clazz.getName().replace('.', '/');
  }
}
