package com.datadog.debugger.agent;

import static com.datadog.debugger.agent.SourceFileTrackingTransformer.MAX_QUEUE_SIZE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static utils.TestClassFileHelper.getClassFileBytes;

import com.datadog.debugger.probe.LogProbe;
import datadog.trace.api.Config;
import java.lang.instrument.IllegalClassFormatException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import utils.TestHelper;

class SourceFileTrackingTransformerTest {
  @Test
  void transformTopLevel() throws IllegalClassFormatException {
    TestHelper.setFieldInConfig(
        Config.get(),
        "debuggerThirdPartyExcludes",
        new HashSet<>(Arrays.asList("com.datadog.debugger.agent")));
    try {
      ClassesToRetransformFinder finder = new ClassesToRetransformFinder();
      SourceFileTrackingTransformer sourceFileTrackingTransformer =
          new SourceFileTrackingTransformer(finder);
      ConfigurationComparer comparer = createComparer("TopLevelHelper.java");
      sourceFileTrackingTransformer.transform(
          null,
          getInternalName(TopLevelHelper.class),
          null,
          null,
          getClassFileBytes(TopLevelHelper.class));
      List<Class<?>> changedClasses =
          finder.getAllLoadedChangedClasses(new Class[] {TopLevelHelper.class}, comparer);
      assertEquals(1, changedClasses.size());
      assertEquals(TopLevelHelper.class, changedClasses.get(0));
      sourceFileTrackingTransformer.transform(
          null,
          getInternalName(MyTopLevelClass.class),
          null,
          null,
          getClassFileBytes(MyTopLevelClass.class));
      sourceFileTrackingTransformer.flush();
      changedClasses =
          finder.getAllLoadedChangedClasses(
              new Class[] {TopLevelHelper.class, MyTopLevelClass.class}, comparer);
      assertEquals(2, changedClasses.size());
      assertEquals(TopLevelHelper.class, changedClasses.get(0));
      assertEquals(MyTopLevelClass.class, changedClasses.get(1));
    } finally {
      TestHelper.setFieldInConfig(
          Config.get(), "debuggerThirdPartyExcludes", Collections.emptySet());
    }
  }

  @Test
  void transformInner() throws IllegalClassFormatException {
    TestHelper.setFieldInConfig(
        Config.get(),
        "debuggerThirdPartyExcludes",
        new HashSet<>(Arrays.asList("com.datadog.debugger.agent")));
    try {
      ClassesToRetransformFinder finder = new ClassesToRetransformFinder();
      SourceFileTrackingTransformer sourceFileTrackingTransformer =
          new SourceFileTrackingTransformer(finder);
      ConfigurationComparer comparer = createComparer("InnerHelper.java");
      sourceFileTrackingTransformer.transform(
          null,
          getInternalName(InnerHelper.class),
          null,
          null,
          getClassFileBytes(InnerHelper.class));
      sourceFileTrackingTransformer.flush();
      List<Class<?>> changedClasses =
          finder.getAllLoadedChangedClasses(new Class[] {InnerHelper.class}, comparer);
      assertEquals(1, changedClasses.size());
      assertEquals(InnerHelper.class, changedClasses.get(0));
      sourceFileTrackingTransformer.transform(
          null,
          getInternalName(InnerHelper.MyInner.class),
          null,
          null,
          getClassFileBytes(InnerHelper.MyInner.class));
      sourceFileTrackingTransformer.transform(
          null,
          getInternalName(InnerHelper.MySecondInner.class),
          null,
          null,
          getClassFileBytes(InnerHelper.MySecondInner.class));
      sourceFileTrackingTransformer.flush();
      changedClasses =
          finder.getAllLoadedChangedClasses(
              new Class[] {
                InnerHelper.class, InnerHelper.MyInner.class, InnerHelper.MySecondInner.class
              },
              comparer);
      assertEquals(3, changedClasses.size());
      assertEquals(InnerHelper.class, changedClasses.get(0));
      assertEquals(InnerHelper.MyInner.class, changedClasses.get(1));
      assertEquals(InnerHelper.MySecondInner.class, changedClasses.get(2));
    } finally {
      TestHelper.setFieldInConfig(
          Config.get(), "debuggerThirdPartyExcludes", Collections.emptySet());
    }
  }

  @Test
  void transformNotAllowed() throws IllegalClassFormatException {
    ClassesToRetransformFinder finder = new ClassesToRetransformFinder();
    SourceFileTrackingTransformer sourceFileTrackingTransformer =
        new SourceFileTrackingTransformer(finder);
    ConfigurationComparer comparer = createComparer("TopLevelHelper.java");
    byte[] classFileBytes = getClassFileBytes(TopLevelHelper.class);
    replaceInByteArray(
        classFileBytes, "TopLevelHelper.java".getBytes(), "TopLevelHelper.cloj".getBytes());
    sourceFileTrackingTransformer.transform(null, "", null, null, classFileBytes);
    sourceFileTrackingTransformer.flush();
    List<Class<?>> changedClasses =
        finder.getAllLoadedChangedClasses(new Class[] {InnerHelper.class}, comparer);
    assertEquals(0, finder.getClassNamesBySourceFile().size());
  }

  @Test
  void thirdPartyExcludes() throws IllegalClassFormatException {
    ClassesToRetransformFinder finder = new ClassesToRetransformFinder();
    SourceFileTrackingTransformer sourceFileTrackingTransformer =
        new SourceFileTrackingTransformer(finder);
    ConfigurationComparer comparer = createComparer("TopLevelHelper.java");
    sourceFileTrackingTransformer.transform(
        null,
        getInternalName(TopLevelHelper.class),
        null,
        null,
        getClassFileBytes(TopLevelHelper.class));
    sourceFileTrackingTransformer.flush();
    assertEquals(0, finder.getClassNamesBySourceFile().size());
  }

  @Test
  void maxQueue() throws IllegalClassFormatException {
    ClassesToRetransformFinder finder = new ClassesToRetransformFinder();
    SourceFileTrackingTransformer sourceFileTrackingTransformer =
        new SourceFileTrackingTransformer(finder);
    for (int i = 0; i < MAX_QUEUE_SIZE + 10; i++) {
      sourceFileTrackingTransformer.transform(
          null,
          getInternalName(TopLevelHelper.class),
          null,
          null,
          getClassFileBytes(TopLevelHelper.class));
    }
    assertEquals(MAX_QUEUE_SIZE, sourceFileTrackingTransformer.getQueueSize());
  }

  private static void replaceInByteArray(byte[] buffer, byte[] oldBytes, byte[] newBytes) {
    int oldIdx = 0;
    for (int i = 0; i < buffer.length; i++) {
      if (buffer[i] == oldBytes[oldIdx]) {
        oldIdx++;
        if (oldIdx == oldBytes.length) {
          // Found the oldBytes, replace with newBytes
          System.arraycopy(newBytes, 0, buffer, i - oldIdx + 1, newBytes.length);
          oldIdx = 0; // Reset for next search
        }
      } else {
        oldIdx = 0; // Reset if current byte does not match
      }
    }
  }

  private ConfigurationComparer createComparer(String sourceFile) {
    Configuration emptyConfig = Configuration.builder().setService("service-name").build();
    Configuration newConfig =
        Configuration.builder()
            .setService("service-name")
            .add(new LogProbe.Builder().probeId("", 1).where(sourceFile, 42).build())
            .build();
    return new ConfigurationComparer(emptyConfig, newConfig, new HashMap<>());
  }

  private String getInternalName(Class<?> clazz) {
    return clazz.getName().replace('.', '/');
  }
}
