package com.datadog.debugger.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

public class TransformerDefinitionMatcherTest {
  private static final String SERVICE_NAME = "service-name";
  private static final long ORG_ID = 2;
  private static final String PROBE_ID1 = "beae1807-f3b0-4ea8-a74f-826790c5e6f6";
  private static final String PROBE_ID2 = "beae1807-f3b0-4ea8-a74f-826790c5e6f7";

  @Test
  public void empty() {
    TransformerDefinitionMatcher matcher =
        createMatcher(Collections.emptyList(), Collections.emptyList());
    assertTrue(matcher.isEmpty());
  }

  @Test
  public void fullQualifiedClassName() {
    SnapshotProbe probe = createProbe(PROBE_ID1, "java.lang.String", "indexOf");
    TransformerDefinitionMatcher matcher =
        createMatcher(Arrays.asList(probe), Collections.emptyList());
    List<ProbeDefinition> probeDefinitions = match(matcher, String.class);
    assertEquals(1, probeDefinitions.size());
    assertEquals(PROBE_ID1, probeDefinitions.get(0).getId());
  }

  @Test
  public void simpleClassName() {
    SnapshotProbe probe = createProbe(PROBE_ID1, "String", "indexOf");
    TransformerDefinitionMatcher matcher =
        createMatcher(Arrays.asList(probe), Collections.emptyList());
    List<ProbeDefinition> probeDefinitions = match(matcher, String.class);
    assertEquals(1, probeDefinitions.size());
    assertEquals(PROBE_ID1, probeDefinitions.get(0).getId());
  }

  @Test
  public void simpleClassNameNoClassRedefined() {
    SnapshotProbe probe = createProbe(PROBE_ID1, "String", "indexOf");
    TransformerDefinitionMatcher matcher =
        createMatcher(Arrays.asList(probe), Collections.emptyList());
    List<ProbeDefinition> probeDefinitions =
        matcher.match(
            null,
            getClassPath(String.class),
            String.class.getName(),
            getClassFileBytes(String.class));
    assertEquals(1, probeDefinitions.size());
    assertEquals(PROBE_ID1, probeDefinitions.get(0).getId());
  }

  @Test
  public void sourceFileFullFileName() {
    SnapshotProbe probe = createProbe(PROBE_ID1, "src/main/java/java/lang/String.java", 23);
    TransformerDefinitionMatcher matcher =
        createMatcher(Arrays.asList(probe), Collections.emptyList());
    List<ProbeDefinition> probeDefinitions = match(matcher, String.class);
    assertEquals(1, probeDefinitions.size());
    assertEquals(PROBE_ID1, probeDefinitions.get(0).getId());
  }

  @Test
  public void sourceFileSimpleFileName() {
    SnapshotProbe probe = createProbe(PROBE_ID1, "String.java", 23);
    TransformerDefinitionMatcher matcher =
        createMatcher(Arrays.asList(probe), Collections.emptyList());
    List<ProbeDefinition> probeDefinitions = match(matcher, String.class);
    assertEquals(1, probeDefinitions.size());
    assertEquals(PROBE_ID1, probeDefinitions.get(0).getId());
  }

  @Test
  public void multiProbesFQN() {
    SnapshotProbe probe1 = createProbe(PROBE_ID1, "java.lang.String", "indexOf");
    SnapshotProbe probe2 = createProbe(PROBE_ID2, "java.lang.String", "substring");
    TransformerDefinitionMatcher matcher =
        createMatcher(Arrays.asList(probe1, probe2), Collections.emptyList());
    List<ProbeDefinition> probeDefinitions = match(matcher, String.class);
    assertEquals(2, probeDefinitions.size());
    assertEquals(PROBE_ID1, probeDefinitions.get(0).getId());
    assertEquals(PROBE_ID2, probeDefinitions.get(1).getId());
  }

  @Test
  public void multiProbesSimpleName() {
    SnapshotProbe probe1 = createProbe(PROBE_ID1, "String", "indexOf");
    SnapshotProbe probe2 = createProbe(PROBE_ID2, "String", "substring");
    TransformerDefinitionMatcher matcher =
        createMatcher(Arrays.asList(probe1, probe2), Collections.emptyList());
    List<ProbeDefinition> probeDefinitions = match(matcher, String.class);
    assertEquals(2, probeDefinitions.size());
    assertEquals(PROBE_ID1, probeDefinitions.get(0).getId());
    assertEquals(PROBE_ID2, probeDefinitions.get(1).getId());
  }

  @Test
  public void multiProbesSourceFile() {
    SnapshotProbe probe1 = createProbe(PROBE_ID1, "src/main/java/java/lang/String.java", 23);
    SnapshotProbe probe2 = createProbe(PROBE_ID2, "src/main/java/java/lang/String.java", 42);
    TransformerDefinitionMatcher matcher =
        createMatcher(Arrays.asList(probe1, probe2), Collections.emptyList());
    List<ProbeDefinition> probeDefinitions = match(matcher, String.class);
    assertEquals(2, probeDefinitions.size());
    assertEquals(PROBE_ID1, probeDefinitions.get(0).getId());
    assertEquals(PROBE_ID2, probeDefinitions.get(1).getId());
  }

  @Test
  public void mixedProbesFQNSimple() {
    SnapshotProbe probe1 = createProbe(PROBE_ID1, "java.lang.String", "indexOf");
    SnapshotProbe probe2 = createProbe(PROBE_ID2, "String", "substring");
    TransformerDefinitionMatcher matcher =
        createMatcher(Arrays.asList(probe1, probe2), Collections.emptyList());
    List<ProbeDefinition> probeDefinitions = match(matcher, String.class);
    assertEquals(2, probeDefinitions.size());
    assertEquals(PROBE_ID1, probeDefinitions.get(0).getId());
    assertEquals(PROBE_ID2, probeDefinitions.get(1).getId());
  }

  @Test
  public void mixedSnapshotMetricProbes() {
    SnapshotProbe probe1 = createProbe(PROBE_ID1, "java.lang.String", "indexOf");
    MetricProbe probe2 = createMetric(PROBE_ID2, "String", "substring");
    TransformerDefinitionMatcher matcher =
        createMatcher(Arrays.asList(probe1), Arrays.asList(probe2));
    List<ProbeDefinition> probeDefinitions = match(matcher, String.class);
    assertEquals(2, probeDefinitions.size());
    assertEquals(PROBE_ID1, probeDefinitions.get(0).getId());
    assertEquals(PROBE_ID2, probeDefinitions.get(1).getId());
  }

  private TransformerDefinitionMatcher createMatcher(
      Collection<SnapshotProbe> snapshotProbes, Collection<MetricProbe> metricProbes) {
    return new TransformerDefinitionMatcher(
        new Configuration(SERVICE_NAME, ORG_ID, snapshotProbes, metricProbes));
  }

  private static List<ProbeDefinition> match(TransformerDefinitionMatcher matcher, Class<?> clazz) {
    return matcher.match(clazz, getClassPath(clazz), clazz.getName(), getClassFileBytes(clazz));
  }

  private SnapshotProbe createProbe(String probeId, String typeName, String methodName) {
    return SnapshotProbe.builder().probeId(probeId).where(typeName, methodName).build();
  }

  private SnapshotProbe createProbe(String probeId, String sourceFileName, int line) {
    return SnapshotProbe.builder()
        .probeId(probeId)
        .where(null, null, null, line, sourceFileName)
        .build();
  }

  private MetricProbe createMetric(String probeId, String typeName, String methodName) {
    return MetricProbe.builder()
        .metricId(probeId)
        .where(typeName, methodName)
        .metricName("count")
        .kind(MetricProbe.MetricKind.COUNT)
        .build();
  }

  private static String getClassPath(Class<?> clazz) {
    return clazz.getName().replace('.', '/');
  }

  private static byte[] getClassFileBytes(Class<?> clazz) {
    URL resource = clazz.getResource(clazz.getSimpleName() + ".class");
    byte[] buffer = new byte[4096];
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    try (InputStream is = resource.openStream()) {
      int readBytes;
      while ((readBytes = is.read(buffer)) != -1) {
        os.write(buffer, 0, readBytes);
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
    return os.toByteArray();
  }
}
