package com.datadog.debugger.agent;

import static com.datadog.debugger.agent.Trie.reverseStr;
import static org.junit.jupiter.api.Assertions.*;

import com.datadog.debugger.instrumentation.InstrumentationResult;
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
  private static final String PROBE_ID = "beae1807-f3b0-4ea8-a74f-826790c5e6f8";
  private static final String SERVICE_NAME = "service-name";
  private static final long ORG_ID = 2;

  @Test
  public void newDefinitions() {
    Configuration empty = createConfig(Collections.emptyList());
    SnapshotProbe probe =
        SnapshotProbe.builder()
            .probeId(PROBE_ID)
            .where(null, null, null, 1966, "src/main/java/java/lang/String.java")
            .build();
    ConfigurationComparer configurationComparer =
        new ConfigurationComparer(
            empty, createConfig(Collections.singletonList(probe)), Collections.emptyMap());
    Collection<ProbeDefinition> addedDefinitions = configurationComparer.getAddedDefinitions();
    Assertions.assertEquals(1, addedDefinitions.size());
    Assertions.assertTrue(addedDefinitions.contains(probe));
    Assertions.assertTrue(configurationComparer.getRemovedDefinitions().isEmpty());
  }

  @Test
  public void removeDefinitions() {
    Configuration empty = createConfig(Collections.emptyList());
    SnapshotProbe probe =
        SnapshotProbe.builder()
            .probeId(PROBE_ID)
            .where(null, null, null, 1966, "src/main/java/java/lang/String.java")
            .build();
    ConfigurationComparer configurationComparer =
        new ConfigurationComparer(
            createConfig(Collections.singletonList(probe)), empty, Collections.emptyMap());
    Collection<ProbeDefinition> removedDefinitions = configurationComparer.getRemovedDefinitions();
    Assertions.assertEquals(1, removedDefinitions.size());
    Assertions.assertTrue(removedDefinitions.contains(probe));
    Assertions.assertTrue(configurationComparer.getAddedDefinitions().isEmpty());
  }

  @Test
  public void hasProbeRelatedChangesEmpty() {
    Configuration empty = createConfig(Collections.emptyList());
    ConfigurationComparer configurationComparer =
        new ConfigurationComparer(empty, empty, Collections.emptyMap());
    Assertions.assertFalse(configurationComparer.hasProbeRelatedChanges());
  }

  @Test
  public void hasProbeRelatedChangesSame() {
    SnapshotProbe probe =
        SnapshotProbe.builder()
            .probeId(PROBE_ID)
            .where(null, null, null, 1966, "src/main/java/java/lang/String.java")
            .build();
    Configuration config = createConfig(Collections.singletonList(probe));
    ConfigurationComparer configurationComparer =
        new ConfigurationComparer(config, config, Collections.emptyMap());
    Assertions.assertFalse(configurationComparer.hasProbeRelatedChanges());
  }

  @Test
  public void hasProbeRelatedChangesAdded() {
    Configuration empty = createConfig(Collections.emptyList());
    SnapshotProbe probe =
        SnapshotProbe.builder()
            .probeId(PROBE_ID)
            .where(null, null, null, 1966, "src/main/java/java/lang/String.java")
            .build();
    Configuration config = createConfig(Collections.singletonList(probe));
    ConfigurationComparer configurationComparer =
        new ConfigurationComparer(empty, config, Collections.emptyMap());
    Assertions.assertTrue(configurationComparer.hasProbeRelatedChanges());
    Assertions.assertFalse(configurationComparer.getAddedDefinitions().isEmpty());
    Assertions.assertTrue(configurationComparer.getRemovedDefinitions().isEmpty());
  }

  @Test
  public void hasProbeRelatedChangesRemoved() {
    Configuration empty = createConfig(Collections.emptyList());
    SnapshotProbe probe =
        SnapshotProbe.builder()
            .probeId(PROBE_ID)
            .where(null, null, null, 1966, "src/main/java/java/lang/String.java")
            .build();
    Configuration config = createConfig(Collections.singletonList(probe));
    ConfigurationComparer configurationComparer =
        new ConfigurationComparer(config, empty, Collections.emptyMap());
    Assertions.assertTrue(configurationComparer.hasProbeRelatedChanges());
    Assertions.assertTrue(configurationComparer.getAddedDefinitions().isEmpty());
    Assertions.assertFalse(configurationComparer.getRemovedDefinitions().isEmpty());
  }

  @Test
  public void addDuplicate() {
    SnapshotProbe probe =
        SnapshotProbe.builder()
            .probeId(PROBE_ID)
            .where(null, null, null, 1966, "src/main/java/java/lang/String.java")
            .build();
    Configuration singleProbeConfig = createConfig(Collections.singletonList(probe));
    SnapshotProbe duplicatedProbe =
        SnapshotProbe.builder()
            .probeId(PROBE_ID)
            .where(null, null, null, 1966, "src/main/java/java/lang/String.java")
            .build();
    duplicatedProbe.addAdditionalProbe(probe);
    Configuration duplicatedProbeConfig = createConfig(Collections.singletonList(duplicatedProbe));
    ConfigurationComparer configurationComparer =
        new ConfigurationComparer(singleProbeConfig, duplicatedProbeConfig, Collections.emptyMap());
    Assertions.assertTrue(configurationComparer.hasProbeRelatedChanges());
    Assertions.assertFalse(configurationComparer.getAddedDefinitions().isEmpty());
    ProbeDefinition added = configurationComparer.getAddedDefinitions().iterator().next();
    Assertions.assertEquals(duplicatedProbe, added);
    Assertions.assertFalse(configurationComparer.getRemovedDefinitions().isEmpty());
    ProbeDefinition removed = configurationComparer.getRemovedDefinitions().iterator().next();
    Assertions.assertEquals(probe, removed);
  }

  @Test
  public void removeDuplicate() {
    SnapshotProbe probe =
        SnapshotProbe.builder()
            .probeId(PROBE_ID)
            .where(null, null, null, 1966, "src/main/java/java/lang/String.java")
            .build();
    Configuration singleProbeConfig = createConfig(Collections.singletonList(probe));
    SnapshotProbe duplicatedProbe =
        SnapshotProbe.builder()
            .probeId(PROBE_ID)
            .where(null, null, null, 1966, "src/main/java/java/lang/String.java")
            .build();
    duplicatedProbe.addAdditionalProbe(probe);
    Configuration duplicatedProbeConfig = createConfig(Collections.singletonList(duplicatedProbe));
    ConfigurationComparer configurationComparer =
        new ConfigurationComparer(duplicatedProbeConfig, singleProbeConfig, Collections.emptyMap());
    Assertions.assertTrue(configurationComparer.hasProbeRelatedChanges());
    Assertions.assertFalse(configurationComparer.getAddedDefinitions().isEmpty());
    ProbeDefinition added = configurationComparer.getAddedDefinitions().iterator().next();
    Assertions.assertEquals(probe, added);
    Assertions.assertFalse(configurationComparer.getRemovedDefinitions().isEmpty());
    ProbeDefinition removed = configurationComparer.getRemovedDefinitions().iterator().next();
    Assertions.assertEquals(duplicatedProbe, removed);
  }

  @Test
  public void hasProbeRelatedChangesFilteredChanged() {
    Configuration empty = createConfig(Collections.emptyList());
    Configuration config =
        new Configuration(
            SERVICE_NAME,
            ORG_ID,
            Collections.emptyList(),
            Collections.emptyList(),
            new Configuration.FilterList(Arrays.asList("com.datadog"), Collections.emptyList()),
            null,
            null,
            null);
    ConfigurationComparer configurationComparer =
        new ConfigurationComparer(empty, config, Collections.emptyMap());
    Assertions.assertTrue(configurationComparer.hasProbeRelatedChanges());
  }

  @Test
  public void hasProbeRelatedChangesWhenAllowListAddedWithProbe() {
    SnapshotProbe probe =
        SnapshotProbe.builder()
            .probeId(PROBE_ID)
            .where("com.datadog.Blocked", "method", null)
            .build();

    Map<String, InstrumentationResult> instrumentationResults = new HashMap<>();
    instrumentationResults.put(
        probe.getId(), InstrumentationResult.Factory.blocked(probe.getWhere().getTypeName()));
    Configuration noFilterConfig = createConfig(Collections.singletonList(probe));
    Configuration config =
        new Configuration(
            SERVICE_NAME,
            ORG_ID,
            Collections.singletonList(probe),
            Collections.emptyList(),
            new Configuration.FilterList(Arrays.asList("com.datadog"), Collections.emptyList()),
            null,
            null,
            null);
    ConfigurationComparer configurationComparer =
        new ConfigurationComparer(noFilterConfig, config, instrumentationResults);
    Assertions.assertTrue(configurationComparer.hasProbeRelatedChanges());
    Assertions.assertTrue(configurationComparer.getAllChangedClasses().isEmpty());

    Configuration changedAllowedList =
        new Configuration(
            SERVICE_NAME,
            ORG_ID,
            Collections.singletonList(probe),
            Collections.emptyList(),
            new Configuration.FilterList(Arrays.asList("com.datacat"), Collections.emptyList()),
            null,
            null,
            null);

    ConfigurationComparer configurationComparer2 =
        new ConfigurationComparer(config, changedAllowedList, instrumentationResults);
    Assertions.assertTrue(configurationComparer2.hasProbeRelatedChanges());
    Assertions.assertTrue(configurationComparer2.getAddedDefinitions().isEmpty());
    Assertions.assertTrue(configurationComparer2.getRemovedDefinitions().isEmpty());
    Assertions.assertTrue(
        configurationComparer2
            .getAllChangedClasses()
            .contains(reverseStr(probe.getWhere().getTypeName())));
  }

  @Test
  public void hasProbeRelatedChangesWhenDenyListAddedWithProbe() {
    SnapshotProbe probe =
        SnapshotProbe.builder()
            .probeId(PROBE_ID)
            .where("com.datadog.Blocked", "method", null)
            .build();

    Map<String, InstrumentationResult> instrumentationResults = new HashMap<>();
    instrumentationResults.put(
        probe.getId(),
        new InstrumentationResult(
            InstrumentationResult.Status.INSTALLED, null, "com.datadog.Blocked", "method"));
    Configuration noFilterConfig = createConfig(Collections.singletonList(probe));
    Configuration config =
        new Configuration(
            SERVICE_NAME,
            ORG_ID,
            Collections.singletonList(probe),
            Collections.emptyList(),
            null,
            new Configuration.FilterList(Arrays.asList("com.datadog"), Collections.emptyList()),
            null,
            null);
    ConfigurationComparer configurationComparer =
        new ConfigurationComparer(noFilterConfig, config, instrumentationResults);
    Assertions.assertTrue(configurationComparer.hasProbeRelatedChanges());
    Assertions.assertTrue(
        configurationComparer
            .getAllChangedClasses()
            .contains(reverseStr(probe.getWhere().getTypeName())));
  }

  @Test
  public void hasProbeRelatedChangesWhenChangeDenyListAndAddingProbe() {
    SnapshotProbe probe =
        SnapshotProbe.builder()
            .probeId(PROBE_ID)
            .where("com.datadog.Blocked", "method", null)
            .build();

    Map<String, InstrumentationResult> instrumentationResults = new HashMap<>();

    // first: add a new probe that is going to be filtered
    instrumentationResults.put(
        probe.getId(), InstrumentationResult.Factory.blocked(probe.getWhere().getTypeName()));
    Configuration empty = createConfig(Collections.emptyList());
    Configuration config =
        new Configuration(
            SERVICE_NAME,
            ORG_ID,
            Collections.singletonList(probe),
            Collections.emptyList(),
            null,
            new Configuration.FilterList(Arrays.asList("com.datadog"), Collections.emptyList()),
            null,
            null);
    ConfigurationComparer configurationComparer =
        new ConfigurationComparer(empty, config, Collections.emptyMap());
    Assertions.assertTrue(configurationComparer.hasProbeRelatedChanges());
    Assertions.assertTrue(
        configurationComparer
            .getAllChangedClasses()
            .contains(reverseStr(probe.getWhere().getTypeName())));

    // remove the filtered list and see it will be re-transformed.
    Configuration noFilterConfig = createConfig(Collections.singletonList(probe));
    ConfigurationComparer configurationComparer2 =
        new ConfigurationComparer(config, noFilterConfig, instrumentationResults);
    Assertions.assertTrue(configurationComparer2.hasProbeRelatedChanges());
    Assertions.assertTrue(
        configurationComparer2
            .getAllChangedClasses()
            .contains(reverseStr(probe.getWhere().getTypeName())));
  }

  @Test
  public void changedClassesFQClassName() {
    Configuration empty = createConfig(Collections.emptyList());
    Configuration config =
        createConfig(
            Arrays.asList(
                SnapshotProbe.builder()
                    .probeId(PROBE_ID)
                    .where("java.lang.String", "indexOf", null)
                    .build()));
    ConfigurationComparer configurationComparer =
        new ConfigurationComparer(empty, config, Collections.emptyMap());
    Trie allChangedClasses = configurationComparer.getAllChangedClasses();
    Assertions.assertTrue(allChangedClasses.contains(reverseStr("java.lang.String")));
  }

  @Test
  public void changedClassesSimpleClassName() {
    Configuration empty = createConfig(Collections.emptyList());
    Configuration config =
        createConfig(
            Arrays.asList(
                SnapshotProbe.builder()
                    .probeId(PROBE_ID)
                    .where("String", "indexOf", null)
                    .build()));
    ConfigurationComparer configurationComparer =
        new ConfigurationComparer(empty, config, Collections.emptyMap());
    Trie allChangedClasses = configurationComparer.getAllChangedClasses();
    Assertions.assertTrue(allChangedClasses.contains(reverseStr("String")));
  }

  @Test
  public void changedClassesFullPath() {
    Configuration empty = createConfig(Collections.emptyList());
    Configuration config =
        createConfig(
            Arrays.asList(
                SnapshotProbe.builder()
                    .probeId(PROBE_ID)
                    .where(null, null, null, 1966, "src/main/java/java/lang/String.java")
                    .build()));
    ConfigurationComparer configurationComparer =
        new ConfigurationComparer(empty, config, Collections.emptyMap());
    Trie allChangedClasses = configurationComparer.getAllChangedClasses();
    Assertions.assertTrue(allChangedClasses.containsPrefix(reverseStr("String")));
    Assertions.assertTrue(allChangedClasses.containsPrefix(reverseStr("java.lang.String")));
  }

  @Test
  public void allLoadedChangedClassesType() {
    SnapshotProbe probe =
        SnapshotProbe.builder().probeId(PROBE_ID).where("java.lang.String", "indexOf").build();
    doAllLoadedChangedClasses(probe, String.class, false, String.class, HashMap.class);
    doAllLoadedChangedClasses(probe, null, false, HashMap.class);
  }

  @Test
  public void allLoadedChangedClassesSimpleType() {
    SnapshotProbe probe =
        SnapshotProbe.builder().probeId(PROBE_ID).where("String", "indexOf").build();
    doAllLoadedChangedClasses(probe, String.class, false, String.class, HashMap.class);
    doAllLoadedChangedClasses(probe, null, false, HashMap.class);
  }

  @Test
  public void allLoadedChangedClasses() {
    SnapshotProbe probe =
        SnapshotProbe.builder()
            .probeId(PROBE_ID)
            .where(null, null, null, 1966, "src/main/java/java/lang/String.java")
            .build();
    doAllLoadedChangedClasses(probe, String.class, false, String.class, HashMap.class);
    doAllLoadedChangedClasses(probe, null, false, HashMap.class);
  }

  @Test
  public void allLoadedChangedClassesSimpleFileName() {
    SnapshotProbe probe =
        SnapshotProbe.builder()
            .probeId(PROBE_ID)
            .where(null, null, null, 1966, "String.java")
            .build();
    doAllLoadedChangedClasses(probe, String.class, false, String.class, HashMap.class);
    doAllLoadedChangedClasses(probe, null, false, HashMap.class);
  }

  @Test
  public void allLoadedChangedClassesTypeTopLevelClass() {
    SnapshotProbe probe =
        SnapshotProbe.builder()
            .probeId(PROBE_ID)
            .where("com.datadog.debugger.agent.MyTopLevelClass", "process")
            .build();
    doAllLoadedChangedClasses(probe, MyTopLevelClass.class, true, MyTopLevelClass.class);
  }

  @Test
  public void allLoadedChangedClassesSimpleTypeTopLevelClass() {
    SnapshotProbe probe =
        SnapshotProbe.builder().probeId(PROBE_ID).where("MyTopLevelClass", "process").build();
    doAllLoadedChangedClasses(probe, MyTopLevelClass.class, true, MyTopLevelClass.class);
  }

  @Test
  public void allLoadedChangedClassesTopLevelClassFileName() {
    SnapshotProbe probe =
        SnapshotProbe.builder()
            .probeId(PROBE_ID)
            .where(null, null, null, 8, "com/datadog/debugger/agent/TopLevelHelper.java")
            .build();
    doAllLoadedChangedClasses(probe, MyTopLevelClass.class, true, MyTopLevelClass.class);
  }

  @Test
  public void allLoadedChangedClassesTopLevelClassSimpleFileName() {
    SnapshotProbe probe =
        SnapshotProbe.builder()
            .probeId(PROBE_ID)
            .where(null, null, null, 8, "TopLevelHelper.java")
            .build();
    doAllLoadedChangedClasses(probe, TopLevelHelper.class, false, TopLevelHelper.class);
  }

  private void doAllLoadedChangedClasses(
      SnapshotProbe probe,
      Class<?> expectedClass,
      boolean withInstrumentationResult,
      Class<?>... loadedClass) {
    Configuration empty = createConfig(Collections.emptyList());
    Configuration config = createConfig(Collections.singletonList(probe));
    Map<String, InstrumentationResult> resultMap = Collections.emptyMap();
    if (expectedClass != null && withInstrumentationResult) {
      resultMap = new HashMap<>();
      ClassNode classNode = new ClassNode();
      classNode.name = expectedClass.getName().replace('.', '/'); // ASM stores with '/' notation
      resultMap.put(
          PROBE_ID,
          new InstrumentationResult(
              InstrumentationResult.Status.INSTALLED,
              Collections.emptyList(),
              classNode,
              new MethodNode()));
    }
    ConfigurationComparer configurationComparer =
        new ConfigurationComparer(empty, config, resultMap);
    List<Class<?>> allLoadedChangedClasses =
        configurationComparer.getAllLoadedChangedClasses(loadedClass);
    if (expectedClass != null) {
      assertEquals(1, allLoadedChangedClasses.size());
      assertEquals(expectedClass, allLoadedChangedClasses.get(0));
    } else {
      assertTrue(allLoadedChangedClasses.isEmpty());
    }
  }

  private static Configuration createConfig(List<SnapshotProbe> snapshotProbes) {
    return new Configuration(SERVICE_NAME, ORG_ID, snapshotProbes);
  }
}
