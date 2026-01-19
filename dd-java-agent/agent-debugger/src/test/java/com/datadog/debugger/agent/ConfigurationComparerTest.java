package com.datadog.debugger.agent;

import static com.datadog.debugger.agent.Trie.reverseStr;
import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.Assertions.*;

import com.datadog.debugger.instrumentation.InstrumentationResult;
import com.datadog.debugger.instrumentation.MethodInfo;
import com.datadog.debugger.probe.LogProbe;
import com.datadog.debugger.probe.ProbeDefinition;
import datadog.trace.bootstrap.debugger.ProbeId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

class ConfigurationComparerTest {
  private static final ProbeId PROBE_ID = new ProbeId("beae1807-f3b0-4ea8-a74f-826790c5e6f8", 0);
  private static final ProbeId PROBE_ID1 = new ProbeId("beae1807-f3b0-4ea8-a74f-826790c5e6f6", 0);
  private static final ProbeId PROBE_ID2 = new ProbeId("beae1807-f3b0-4ea8-a74f-826790c5e6f7", 0);
  private static final String SERVICE_NAME = "service-name";

  @Test
  public void newDefinitions() {
    Configuration empty = createConfig(Collections.emptyList());
    LogProbe probe =
        LogProbe.builder()
            .probeId(PROBE_ID)
            .where(null, null, null, 1966, "src/main/java/java/lang/String.java")
            .build();
    ConfigurationComparer configurationComparer =
        new ConfigurationComparer(
            empty, createConfig(Collections.singletonList(probe)), emptyMap());
    Collection<ProbeDefinition> addedDefinitions = configurationComparer.getAddedDefinitions();
    Assertions.assertEquals(1, addedDefinitions.size());
    Assertions.assertTrue(addedDefinitions.contains(probe));
    Assertions.assertTrue(configurationComparer.getRemovedDefinitions().isEmpty());
  }

  @Test
  public void removeDefinitions() {
    Configuration empty = createConfig(Collections.emptyList());
    LogProbe probe =
        LogProbe.builder()
            .probeId(PROBE_ID)
            .where(null, null, null, 1966, "src/main/java/java/lang/String.java")
            .build();
    ConfigurationComparer configurationComparer =
        new ConfigurationComparer(
            createConfig(Collections.singletonList(probe)), empty, emptyMap());
    Collection<ProbeDefinition> removedDefinitions = configurationComparer.getRemovedDefinitions();
    Assertions.assertEquals(1, removedDefinitions.size());
    Assertions.assertTrue(removedDefinitions.contains(probe));
    Assertions.assertTrue(configurationComparer.getAddedDefinitions().isEmpty());
  }

  @Test
  public void hasProbeRelatedChangesEmpty() {
    Configuration empty = createConfig(Collections.emptyList());
    ConfigurationComparer configurationComparer =
        new ConfigurationComparer(empty, empty, emptyMap());
    Assertions.assertFalse(configurationComparer.hasProbeRelatedChanges());
  }

  @Test
  public void hasProbeRelatedChangesSame() {
    LogProbe probe =
        LogProbe.builder()
            .probeId(PROBE_ID)
            .where(null, null, null, 1966, "src/main/java/java/lang/String.java")
            .build();
    Configuration config = createConfig(Collections.singletonList(probe));
    ConfigurationComparer configurationComparer =
        new ConfigurationComparer(config, config, emptyMap());
    Assertions.assertFalse(configurationComparer.hasProbeRelatedChanges());
  }

  @Test
  public void hasProbeRelatedChangesAdded() {
    Configuration empty = createConfig(Collections.emptyList());
    LogProbe probe =
        LogProbe.builder()
            .probeId(PROBE_ID)
            .where(null, null, null, 1966, "src/main/java/java/lang/String.java")
            .build();
    Configuration config = createConfig(Collections.singletonList(probe));
    ConfigurationComparer configurationComparer =
        new ConfigurationComparer(empty, config, emptyMap());
    Assertions.assertTrue(configurationComparer.hasProbeRelatedChanges());
    Assertions.assertFalse(configurationComparer.getAddedDefinitions().isEmpty());
    Assertions.assertTrue(configurationComparer.getRemovedDefinitions().isEmpty());
  }

  @Test
  public void hasProbeRelatedChangesRemoved() {
    Configuration empty = createConfig(Collections.emptyList());
    LogProbe probe =
        LogProbe.builder()
            .probeId(PROBE_ID)
            .where(null, null, null, 1966, "src/main/java/java/lang/String.java")
            .build();
    Configuration config = createConfig(Collections.singletonList(probe));
    ConfigurationComparer configurationComparer =
        new ConfigurationComparer(config, empty, emptyMap());
    Assertions.assertTrue(configurationComparer.hasProbeRelatedChanges());
    Assertions.assertTrue(configurationComparer.getAddedDefinitions().isEmpty());
    Assertions.assertFalse(configurationComparer.getRemovedDefinitions().isEmpty());
  }

  @Test
  public void addDuplicate() {
    LogProbe probe =
        LogProbe.builder()
            .probeId(PROBE_ID1)
            .where(null, null, null, 1966, "src/main/java/java/lang/String.java")
            .build();
    Configuration singleProbeConfig = createConfig(Collections.singletonList(probe));
    LogProbe duplicatedProbe =
        LogProbe.builder()
            .probeId(PROBE_ID2)
            .where(null, null, null, 1966, "src/main/java/java/lang/String.java")
            .build();
    Configuration duplicatedProbeConfig = createConfig(Arrays.asList(probe, duplicatedProbe));
    ConfigurationComparer configurationComparer =
        new ConfigurationComparer(singleProbeConfig, duplicatedProbeConfig, emptyMap());
    Assertions.assertTrue(configurationComparer.hasProbeRelatedChanges());
    Assertions.assertFalse(configurationComparer.getAddedDefinitions().isEmpty());
    ProbeDefinition added = configurationComparer.getAddedDefinitions().iterator().next();
    Assertions.assertEquals(duplicatedProbe, added);
    Assertions.assertTrue(configurationComparer.getRemovedDefinitions().isEmpty());
  }

  @Test
  public void removeDuplicate() {
    LogProbe probe =
        LogProbe.builder()
            .probeId(PROBE_ID1)
            .where(null, null, null, 1966, "src/main/java/java/lang/String.java")
            .build();
    Configuration singleProbeConfig = createConfig(Collections.singletonList(probe));
    LogProbe duplicatedProbe =
        LogProbe.builder()
            .probeId(PROBE_ID2)
            .where(null, null, null, 1966, "src/main/java/java/lang/String.java")
            .build();
    Configuration duplicatedProbeConfig = createConfig(Arrays.asList(probe, duplicatedProbe));
    ConfigurationComparer configurationComparer =
        new ConfigurationComparer(duplicatedProbeConfig, singleProbeConfig, emptyMap());
    Assertions.assertTrue(configurationComparer.hasProbeRelatedChanges());
    Assertions.assertTrue(configurationComparer.getAddedDefinitions().isEmpty());
    Assertions.assertFalse(configurationComparer.getRemovedDefinitions().isEmpty());
    ProbeDefinition removed = configurationComparer.getRemovedDefinitions().iterator().next();
    Assertions.assertEquals(duplicatedProbe, removed);
  }

  @Test
  public void hasProbeRelatedChangesFilteredChanged() {
    Configuration empty = createConfig(Collections.emptyList());
    Configuration config =
        Configuration.builder()
            .setService(SERVICE_NAME)
            .addAllowList(
                new Configuration.FilterList(Arrays.asList("com.datadog"), Collections.emptyList()))
            .build();
    ConfigurationComparer configurationComparer =
        new ConfigurationComparer(empty, config, emptyMap());
    Assertions.assertTrue(configurationComparer.hasProbeRelatedChanges());
  }

  @Test
  public void hasProbeRelatedChangesWhenAllowListAddedWithProbe() {
    LogProbe probe =
        LogProbe.builder().probeId(PROBE_ID).where("com.datadog.Blocked", "method", null).build();

    Map<String, InstrumentationResult> instrumentationResults = new HashMap<>();
    instrumentationResults.put(
        probe.getProbeId().getEncodedId(),
        InstrumentationResult.Factory.blocked(probe.getWhere().getTypeName()));
    Configuration noFilterConfig = createConfig(Collections.singletonList(probe));
    Configuration config =
        Configuration.builder()
            .add(probe)
            .addAllowList(
                new Configuration.FilterList(Arrays.asList("com.datadog"), Collections.emptyList()))
            .build();
    ConfigurationComparer configurationComparer =
        new ConfigurationComparer(noFilterConfig, config, instrumentationResults);
    ClassesToRetransformFinder finder = new ClassesToRetransformFinder();
    Assertions.assertTrue(configurationComparer.hasProbeRelatedChanges());
    Assertions.assertTrue(finder.getAllChangedClasses(configurationComparer).isEmpty());

    Configuration changedAllowedList =
        Configuration.builder()
            .add(probe)
            .addAllowList(
                new Configuration.FilterList(Arrays.asList("com.datacat"), Collections.emptyList()))
            .build();
    ConfigurationComparer configurationComparer2 =
        new ConfigurationComparer(config, changedAllowedList, instrumentationResults);
    Assertions.assertTrue(configurationComparer2.hasProbeRelatedChanges());
    Assertions.assertTrue(configurationComparer2.getAddedDefinitions().isEmpty());
    Assertions.assertTrue(configurationComparer2.getRemovedDefinitions().isEmpty());
    ClassesToRetransformFinder finder2 = new ClassesToRetransformFinder();
    Assertions.assertTrue(
        finder2
            .getAllChangedClasses(configurationComparer2)
            .contains(reverseStr(probe.getWhere().getTypeName())));
  }

  @Test
  public void hasProbeRelatedChangesWhenDenyListAddedWithProbe() {
    LogProbe probe =
        LogProbe.builder().probeId(PROBE_ID).where("com.datadog.Blocked", "method", null).build();

    Map<String, InstrumentationResult> instrumentationResults = new HashMap<>();
    instrumentationResults.put(
        probe.getId(),
        new InstrumentationResult(
            InstrumentationResult.Status.INSTALLED, "com.datadog.Blocked", "method"));
    Configuration noFilterConfig = createConfig(Collections.singletonList(probe));
    Configuration config =
        Configuration.builder()
            .add(probe)
            .addDenyList(
                new Configuration.FilterList(Arrays.asList("com.datadog"), Collections.emptyList()))
            .build();
    ConfigurationComparer configurationComparer =
        new ConfigurationComparer(noFilterConfig, config, instrumentationResults);
    Assertions.assertTrue(configurationComparer.hasProbeRelatedChanges());
    ClassesToRetransformFinder finder = new ClassesToRetransformFinder();
    Assertions.assertTrue(
        finder
            .getAllChangedClasses(configurationComparer)
            .contains(reverseStr(probe.getWhere().getTypeName())));
  }

  @Test
  public void hasProbeRelatedChangesWhenChangeDenyListAndAddingProbe() {
    LogProbe probe =
        LogProbe.builder().probeId(PROBE_ID).where("com.datadog.Blocked", "method", null).build();

    Map<String, InstrumentationResult> instrumentationResults = new HashMap<>();

    // first: add a new probe that is going to be filtered
    instrumentationResults.put(
        probe.getId(), InstrumentationResult.Factory.blocked(probe.getWhere().getTypeName()));
    Configuration empty = createConfig(Collections.emptyList());
    Configuration config =
        Configuration.builder()
            .add(probe)
            .addDenyList(
                new Configuration.FilterList(Arrays.asList("com.datadog"), Collections.emptyList()))
            .build();
    ConfigurationComparer configurationComparer =
        new ConfigurationComparer(empty, config, emptyMap());
    Assertions.assertTrue(configurationComparer.hasProbeRelatedChanges());
    ClassesToRetransformFinder finder = new ClassesToRetransformFinder();
    Assertions.assertTrue(
        finder
            .getAllChangedClasses(configurationComparer)
            .contains(reverseStr(probe.getWhere().getTypeName())));

    // remove the filtered list and see it will be re-transformed.
    Configuration noFilterConfig = createConfig(Collections.singletonList(probe));
    ConfigurationComparer configurationComparer2 =
        new ConfigurationComparer(config, noFilterConfig, instrumentationResults);
    Assertions.assertTrue(configurationComparer2.hasProbeRelatedChanges());
    ClassesToRetransformFinder finder2 = new ClassesToRetransformFinder();
    Assertions.assertTrue(
        finder2
            .getAllChangedClasses(configurationComparer2)
            .contains(reverseStr(probe.getWhere().getTypeName())));
  }

  @Test
  public void changedClassesFQClassName() {
    Configuration empty = createConfig(Collections.emptyList());
    Configuration config =
        createConfig(
            Arrays.asList(
                LogProbe.builder()
                    .probeId(PROBE_ID)
                    .where("java.lang.String", "indexOf", null)
                    .build()));
    ConfigurationComparer configurationComparer =
        new ConfigurationComparer(empty, config, emptyMap());
    ClassesToRetransformFinder finder = new ClassesToRetransformFinder();
    Trie allChangedClasses = finder.getAllChangedClasses(configurationComparer);
    Assertions.assertTrue(allChangedClasses.contains(reverseStr("java.lang.String")));
  }

  @Test
  public void changedClassesSimpleClassName() {
    Configuration empty = createConfig(Collections.emptyList());
    Configuration config =
        createConfig(
            Arrays.asList(
                LogProbe.builder().probeId(PROBE_ID).where("String", "indexOf", null).build()));
    ConfigurationComparer configurationComparer =
        new ConfigurationComparer(empty, config, emptyMap());
    ClassesToRetransformFinder finder = new ClassesToRetransformFinder();
    Trie allChangedClasses = finder.getAllChangedClasses(configurationComparer);
    Assertions.assertTrue(allChangedClasses.contains(reverseStr("String")));
  }

  @Test
  public void changedClassesFullPath() {
    Configuration empty = createConfig(Collections.emptyList());
    Configuration config =
        createConfig(
            Arrays.asList(
                LogProbe.builder()
                    .probeId(PROBE_ID)
                    .where(null, null, null, 1966, "src/main/java/java/lang/String.java")
                    .build()));
    ConfigurationComparer configurationComparer =
        new ConfigurationComparer(empty, config, emptyMap());
    ClassesToRetransformFinder finder = new ClassesToRetransformFinder();
    Trie allChangedClasses = finder.getAllChangedClasses(configurationComparer);
    Assertions.assertTrue(allChangedClasses.containsPrefix(reverseStr("String")));
    Assertions.assertTrue(allChangedClasses.containsPrefix(reverseStr("java.lang.String")));
  }

  @Test
  public void allLoadedChangedClassesType() {
    LogProbe probe =
        LogProbe.builder().probeId(PROBE_ID).where("java.lang.String", "indexOf").build();
    doAllLoadedChangedClasses(probe, emptyMap(), String.class, false, String.class, HashMap.class);
    doAllLoadedChangedClasses(probe, emptyMap(), null, false, HashMap.class);
  }

  @Test
  public void allLoadedChangedClassesSimpleType() {
    LogProbe probe = LogProbe.builder().probeId(PROBE_ID).where("String", "indexOf").build();
    doAllLoadedChangedClasses(probe, emptyMap(), String.class, false, String.class, HashMap.class);
    doAllLoadedChangedClasses(probe, emptyMap(), null, false, HashMap.class);
  }

  @Test
  public void allLoadedChangedClasses() {
    LogProbe probe =
        LogProbe.builder()
            .probeId(PROBE_ID)
            .where(null, null, null, 1966, "src/main/java/java/lang/String.java")
            .build();
    doAllLoadedChangedClasses(probe, emptyMap(), String.class, false, String.class, HashMap.class);
    doAllLoadedChangedClasses(probe, emptyMap(), null, false, HashMap.class);
  }

  @Test
  public void allLoadedChangedClassesSimpleFileName() {
    LogProbe probe =
        LogProbe.builder().probeId(PROBE_ID).where(null, null, null, 1966, "String.java").build();
    doAllLoadedChangedClasses(probe, emptyMap(), String.class, false, String.class, HashMap.class);
    doAllLoadedChangedClasses(probe, emptyMap(), null, false, HashMap.class);
  }

  @Test
  public void allLoadedChangedClassesTypeTopLevelClass() {
    LogProbe probe =
        LogProbe.builder()
            .probeId(PROBE_ID)
            .where("com.datadog.debugger.agent.MyTopLevelClass", "process")
            .build();
    doAllLoadedChangedClasses(
        probe, emptyMap(), MyTopLevelClass.class, true, MyTopLevelClass.class);
  }

  @Test
  public void allLoadedChangedClassesSimpleTypeTopLevelClass() {
    LogProbe probe =
        LogProbe.builder().probeId(PROBE_ID).where("MyTopLevelClass", "process").build();
    doAllLoadedChangedClasses(
        probe, emptyMap(), MyTopLevelClass.class, true, MyTopLevelClass.class);
  }

  @Test
  public void allLoadedChangedClassesTopLevelClassFileName() {
    final String CLASS_FILENAME = "com/datadog/debugger/agent/TopLevelHelper.java";
    Map<String, List<String>> sourceFileMapping = new HashMap<>();
    String simpleSourceFile = CLASS_FILENAME.substring(CLASS_FILENAME.lastIndexOf('/') + 1);
    sourceFileMapping.put(simpleSourceFile, Arrays.asList(MyTopLevelClass.class.getTypeName()));
    LogProbe probe =
        LogProbe.builder().probeId(PROBE_ID).where(null, null, null, 8, CLASS_FILENAME).build();
    doAllLoadedChangedClasses(
        probe, sourceFileMapping, MyTopLevelClass.class, true, MyTopLevelClass.class);
    doAllLoadedChangedClasses(
        probe, sourceFileMapping, MyTopLevelClass.class, false, MyTopLevelClass.class);
  }

  @Test
  public void allLoadedChangedClassesTopLevelClassSimpleFileName() {
    final String CLASS_FILENAME = "TopLevelHelper.java";
    Map<String, List<String>> sourceFileMapping = new HashMap<>();
    sourceFileMapping.put(CLASS_FILENAME, Arrays.asList(MyTopLevelClass.class.getTypeName()));
    LogProbe probe =
        LogProbe.builder().probeId(PROBE_ID).where(null, null, null, 8, CLASS_FILENAME).build();
    doAllLoadedChangedClasses(
        probe, sourceFileMapping, TopLevelHelper.class, false, TopLevelHelper.class);
    doAllLoadedChangedClasses(
        probe, sourceFileMapping, MyTopLevelClass.class, false, MyTopLevelClass.class);
  }

  @Test
  public void allLoadedChangedClassesLargeInnerClasses() {
    final String CLASS_FILENAME = "LargeInnerClasses.java";
    Map<String, List<String>> sourceFileMapping = new HashMap<>();
    List<String> classNames = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      classNames.add(
          String.format("com.datadog.debugger.agent.LargeInnerClasses$MyInnerClass%02d", i));
    }
    sourceFileMapping.put(CLASS_FILENAME, classNames);
    LogProbe probe =
        LogProbe.builder().probeId(PROBE_ID).where(null, null, null, 6, CLASS_FILENAME).build();
    doAllLoadedChangedClasses(
        probe, sourceFileMapping, LargeInnerClasses.class, false, LargeInnerClasses.class);
  }

  private void doAllLoadedChangedClasses(
      LogProbe probe,
      Map<String, List<String>> sourceFileMapping,
      Class<?> expectedClass,
      boolean withInstrumentationResult,
      Class<?>... loadedClass) {
    Configuration empty = createConfig(Collections.emptyList());
    Configuration config = createConfig(Collections.singletonList(probe));
    Map<String, InstrumentationResult> resultMap = emptyMap();
    if (expectedClass != null && withInstrumentationResult) {
      resultMap = new HashMap<>();
      ClassNode classNode = new ClassNode();
      classNode.name = expectedClass.getName().replace('.', '/'); // ASM stores with '/' notation

      resultMap.put(
          PROBE_ID.getId(),
          new InstrumentationResult(
              InstrumentationResult.Status.INSTALLED,
              Collections.emptyMap(),
              new MethodInfo(null, classNode, new MethodNode(), null)));
    }
    ConfigurationComparer configurationComparer =
        new ConfigurationComparer(empty, config, resultMap);
    ClassesToRetransformFinder finder = new ClassesToRetransformFinder();
    for (Map.Entry<String, List<String>> entry : sourceFileMapping.entrySet()) {
      for (String className : entry.getValue()) {
        finder.register(entry.getKey(), className);
      }
    }
    List<Class<?>> allLoadedChangedClasses =
        finder.getAllLoadedChangedClasses(loadedClass, configurationComparer);
    if (expectedClass != null) {
      assertEquals(1, allLoadedChangedClasses.size());
      assertEquals(expectedClass, allLoadedChangedClasses.get(0));
    } else {
      assertTrue(allLoadedChangedClasses.isEmpty());
    }
  }

  private static Configuration createConfig(List<ProbeDefinition> probes) {
    return new Configuration(SERVICE_NAME, probes);
  }
}
