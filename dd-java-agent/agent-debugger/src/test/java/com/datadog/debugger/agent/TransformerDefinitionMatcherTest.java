package com.datadog.debugger.agent;

import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.*;
import static utils.TestClassFileHelper.getClassFileBytes;

import com.datadog.debugger.probe.LogProbe;
import com.datadog.debugger.probe.MetricProbe;
import com.datadog.debugger.probe.ProbeDefinition;
import datadog.trace.bootstrap.debugger.ProbeId;
import java.util.List;
import org.junit.jupiter.api.Test;

public class TransformerDefinitionMatcherTest {
  private static final ProbeId PROBE_ID1 = new ProbeId("beae1807-f3b0-4ea8-a74f-826790c5e6f6", 0);
  private static final ProbeId PROBE_ID2 = new ProbeId("beae1807-f3b0-4ea8-a74f-826790c5e6f7", 0);
  private static final ProbeId PROBE_ID3 = new ProbeId("beae1807-f3b0-4ea8-a74f-826790c5e6f8", 0);
  private static final String SERVICE_NAME = "service-name";

  @Test
  public void empty() {
    TransformerDefinitionMatcher matcher = createMatcher();
    assertTrue(matcher.isEmpty());
  }

  @Test
  public void fullQualifiedClassName() {
    LogProbe probe = createProbe(PROBE_ID1, "java.lang.String", "indexOf");
    TransformerDefinitionMatcher matcher = createMatcher(probe);
    List<ProbeDefinition> probeDefinitions = match(matcher, String.class);
    assertEquals(1, probeDefinitions.size());
    assertEquals(PROBE_ID1, probeDefinitions.get(0).getProbeId());
  }

  @Test
  public void simpleClassName() {
    LogProbe probe = createProbe(PROBE_ID1, "String", "indexOf");
    TransformerDefinitionMatcher matcher = createMatcher(probe);
    List<ProbeDefinition> probeDefinitions = match(matcher, String.class);
    assertEquals(1, probeDefinitions.size());
    assertEquals(PROBE_ID1, probeDefinitions.get(0).getProbeId());
  }

  @Test
  public void simpleClassNameNoClassRedefined() {
    LogProbe probe = createProbe(PROBE_ID1, "String", "indexOf");
    TransformerDefinitionMatcher matcher = createMatcher(probe);
    List<ProbeDefinition> probeDefinitions =
        matcher.match(
            null,
            getClassPath(String.class),
            String.class.getTypeName(),
            getClassFileBytes(String.class));
    assertEquals(1, probeDefinitions.size());
    assertEquals(PROBE_ID1, probeDefinitions.get(0).getProbeId());
  }

  @Test
  public void sourceFileFullFileName() {
    LogProbe probe = createProbe(PROBE_ID1, "src/main/java/java/lang/String.java", 23);
    TransformerDefinitionMatcher matcher = createMatcher(probe);
    List<ProbeDefinition> probeDefinitions = match(matcher, String.class);
    assertEquals(1, probeDefinitions.size());
    assertEquals(PROBE_ID1, probeDefinitions.get(0).getProbeId());
  }

  @Test
  public void sourceFileAbsoluteFileName() {
    LogProbe probe =
        createProbe(PROBE_ID1, "/home/user/project/src/main/java/java/lang/String.java", 23);
    TransformerDefinitionMatcher matcher = createMatcher(probe);
    List<ProbeDefinition> probeDefinitions = match(matcher, String.class);
    assertEquals(1, probeDefinitions.size());
    assertEquals(PROBE_ID1, probeDefinitions.get(0).getProbeId());
  }

  @Test
  public void sourceFileSimpleFileName() {
    LogProbe probe = createProbe(PROBE_ID1, "String.java", 23);
    TransformerDefinitionMatcher matcher = createMatcher(probe);
    List<ProbeDefinition> probeDefinitions = match(matcher, String.class);
    assertEquals(1, probeDefinitions.size());
    assertEquals(PROBE_ID1, probeDefinitions.get(0).getProbeId());
  }

  @Test
  public void multiProbesFQN() {
    LogProbe probe1 = createProbe(PROBE_ID1, "java.lang.String", "indexOf");
    LogProbe probe2 = createProbe(PROBE_ID2, "java.lang.String", "substring");
    TransformerDefinitionMatcher matcher = createMatcher(probe1, probe2);
    List<ProbeDefinition> probeDefinitions = match(matcher, String.class);
    assertEquals(2, probeDefinitions.size());
    assertEquals(PROBE_ID1, probeDefinitions.get(0).getProbeId());
    assertEquals(PROBE_ID2, probeDefinitions.get(1).getProbeId());
  }

  @Test
  public void multiProbesSimpleName() {
    LogProbe probe1 = createProbe(PROBE_ID1, "String", "indexOf");
    LogProbe probe2 = createProbe(PROBE_ID2, "String", "substring");
    TransformerDefinitionMatcher matcher = createMatcher(probe1, probe2);
    List<ProbeDefinition> probeDefinitions = match(matcher, String.class);
    assertEquals(2, probeDefinitions.size());
    assertEquals(PROBE_ID1, probeDefinitions.get(0).getProbeId());
    assertEquals(PROBE_ID2, probeDefinitions.get(1).getProbeId());
  }

  @Test
  public void multiProbesSourceFile() {
    LogProbe probe1 = createProbe(PROBE_ID1, "src/main/java/java/lang/String.java", 23);
    LogProbe probe2 = createProbe(PROBE_ID2, "src/main/java/java/lang/String.java", 42);
    TransformerDefinitionMatcher matcher = createMatcher(probe1, probe2);
    List<ProbeDefinition> probeDefinitions = match(matcher, String.class);
    assertEquals(2, probeDefinitions.size());
    assertEquals(PROBE_ID1, probeDefinitions.get(0).getProbeId());
    assertEquals(PROBE_ID2, probeDefinitions.get(1).getProbeId());
  }

  @Test
  public void mixedProbesFQNSimple() {
    LogProbe probe1 = createProbe(PROBE_ID1, "java.lang.String", "indexOf");
    LogProbe probe2 = createProbe(PROBE_ID2, "String", "substring");
    TransformerDefinitionMatcher matcher = createMatcher(probe1, probe2);
    List<ProbeDefinition> probeDefinitions = match(matcher, String.class);
    assertEquals(2, probeDefinitions.size());
    assertEquals(PROBE_ID1, probeDefinitions.get(0).getProbeId());
    assertEquals(PROBE_ID2, probeDefinitions.get(1).getProbeId());
  }

  @Test
  public void mixedSnapshotMetricProbes() {
    LogProbe probe1 = createProbe(PROBE_ID1, "java.lang.String", "indexOf");
    MetricProbe probe2 = createMetric(PROBE_ID2, "String", "substring");
    TransformerDefinitionMatcher matcher = createMatcher(probe1, probe2);
    List<ProbeDefinition> probeDefinitions = match(matcher, String.class);
    assertEquals(2, probeDefinitions.size());
    assertEquals(PROBE_ID1, probeDefinitions.get(0).getProbeId());
    assertEquals(PROBE_ID2, probeDefinitions.get(1).getProbeId());
  }

  @Test
  public void partialSimpleNameShouldNotMatch() {
    LogProbe probe1 = createProbe(PROBE_ID1, "SuperString.java", 11);
    TransformerDefinitionMatcher matcher = createMatcher(probe1);
    List<ProbeDefinition> probeDefinitions = match(matcher, String.class);
    assertEquals(0, probeDefinitions.size());
  }

  @Test
  public void mixedSourceFileName() {
    LogProbe probe1 = createProbe(PROBE_ID1, "src/main/java/java/lang/String.java", 23);
    LogProbe probe2 = createProbe(PROBE_ID2, "myproject/src/main/java/java/lang/String.java", 42);
    LogProbe probe3 = createProbe(PROBE_ID3, "String.java", 11);
    TransformerDefinitionMatcher matcher = createMatcher(probe1, probe2, probe3);
    List<ProbeDefinition> probeDefinitions = match(matcher, String.class);
    assertEquals(3, probeDefinitions.size());
    assertEquals(PROBE_ID1.getId(), probeDefinitions.get(0).getId());
    assertEquals(PROBE_ID2.getId(), probeDefinitions.get(1).getId());
    assertEquals(PROBE_ID3.getId(), probeDefinitions.get(2).getId());
  }

  private TransformerDefinitionMatcher createMatcher(ProbeDefinition... probes) {
    return new TransformerDefinitionMatcher(new Configuration(SERVICE_NAME, asList(probes)));
  }

  private static List<ProbeDefinition> match(TransformerDefinitionMatcher matcher, Class<?> clazz) {
    return matcher.match(clazz, getClassPath(clazz), clazz.getName(), getClassFileBytes(clazz));
  }

  private LogProbe createProbe(ProbeId probeId, String typeName, String methodName) {
    return LogProbe.builder().probeId(probeId).where(typeName, methodName).build();
  }

  private LogProbe createProbe(ProbeId probeId, String sourceFileName, int line) {
    return LogProbe.builder()
        .probeId(probeId)
        .where(null, null, null, line, sourceFileName)
        .build();
  }

  private MetricProbe createMetric(ProbeId probeId, String typeName, String methodName) {
    return MetricProbe.builder()
        .probeId(probeId)
        .where(typeName, methodName)
        .metricName("count")
        .kind(MetricProbe.MetricKind.COUNT)
        .build();
  }

  private static String getClassPath(Class<?> clazz) {
    return clazz.getName().replace('.', '/');
  }
}
