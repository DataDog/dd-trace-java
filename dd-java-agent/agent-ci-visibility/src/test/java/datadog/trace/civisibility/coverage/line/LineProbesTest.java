package datadog.trace.civisibility.coverage.line;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import datadog.trace.api.civisibility.telemetry.CiVisibilityMetricCollector;
import org.junit.jupiter.api.Test;

class LineProbesTest {

  private static final class ClassA {}

  private static final class ClassB {}

  private final CiVisibilityMetricCollector metrics = mock(CiVisibilityMetricCollector.class);

  @Test
  void resolvesAPerTestArraySizedFromJacocoArray() {
    LineProbes probes = new LineProbes(metrics, true);
    boolean[] jacocoProbes = new boolean[6];

    boolean[] perTest = probes.resolveProbeArray(ClassA.class, 1L, jacocoProbes);

    assertNotSame(jacocoProbes, perTest, "should not record into Jacoco's shared array");
    assertEquals(jacocoProbes.length, perTest.length);
  }

  @Test
  void returnsTheSameArrayForRepeatedResolutionsOfTheSameClass() {
    LineProbes probes = new LineProbes(metrics, true);
    boolean[] jacocoProbes = new boolean[3];

    boolean[] first = probes.resolveProbeArray(ClassA.class, 1L, jacocoProbes);
    boolean[] second = probes.resolveProbeArray(ClassA.class, 1L, jacocoProbes);

    assertSame(first, second);
  }

  @Test
  void keepsSeparateArraysPerClass() {
    LineProbes probes = new LineProbes(metrics, true);

    boolean[] a = probes.resolveProbeArray(ClassA.class, 1L, new boolean[2]);
    boolean[] b = probes.resolveProbeArray(ClassB.class, 2L, new boolean[2]);

    assertNotSame(a, b);
    assertEquals(2, probes.getExecutionData().size());
    assertTrue(probes.getExecutionData().containsKey(ClassA.class));
    assertTrue(probes.getExecutionData().containsKey(ClassB.class));
  }

  @Test
  void perTestWritesDoNotLeakIntoJacocoArrayBeforeReport() {
    LineProbes probes = new LineProbes(metrics, true);
    boolean[] jacocoProbes = new boolean[4];

    boolean[] perTest = probes.resolveProbeArray(ClassA.class, 1L, jacocoProbes);
    perTest[1] = true;

    // the shared array is only updated at report time via
    // ExecutionDataAdapter#mergeIntoJacocoProbes
    assertEquals(false, jacocoProbes[1]);
  }
}
