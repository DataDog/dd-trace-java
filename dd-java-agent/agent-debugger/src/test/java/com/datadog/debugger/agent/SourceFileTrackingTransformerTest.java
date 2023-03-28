package com.datadog.debugger.agent;

import static com.datadog.debugger.util.ClassFileHelperTest.getClassFileBytes;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.datadog.debugger.probe.LogProbe;
import java.lang.instrument.IllegalClassFormatException;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

class SourceFileTrackingTransformerTest {
  @Test
  void transformTopLevel() throws IllegalClassFormatException {
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
    changedClasses =
        finder.getAllLoadedChangedClasses(
            new Class[] {TopLevelHelper.class, MyTopLevelClass.class}, comparer);
    assertEquals(2, changedClasses.size());
    assertEquals(TopLevelHelper.class, changedClasses.get(0));
    assertEquals(MyTopLevelClass.class, changedClasses.get(1));
  }

  @Test
  void transformInner() throws IllegalClassFormatException {
    ClassesToRetransformFinder finder = new ClassesToRetransformFinder();
    SourceFileTrackingTransformer sourceFileTrackingTransformer =
        new SourceFileTrackingTransformer(finder);
    ConfigurationComparer comparer = createComparer("InnerHelper.java");
    sourceFileTrackingTransformer.transform(
        null, getInternalName(InnerHelper.class), null, null, getClassFileBytes(InnerHelper.class));
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
    changedClasses =
        finder.getAllLoadedChangedClasses(
            new Class[] {InnerHelper.class, InnerHelper.MyInner.class}, comparer);
    assertEquals(2, changedClasses.size());
    assertEquals(InnerHelper.class, changedClasses.get(0));
    assertEquals(InnerHelper.MyInner.class, changedClasses.get(1));
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
