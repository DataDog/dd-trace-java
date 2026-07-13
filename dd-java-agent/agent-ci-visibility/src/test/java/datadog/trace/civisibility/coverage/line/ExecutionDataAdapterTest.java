package datadog.trace.civisibility.coverage.line;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ExecutionDataAdapterTest {

  @Test
  void probeActivationsAreSizedFromJacocoArrayAndStartEmpty() {
    boolean[] jacocoProbes = new boolean[4];
    ExecutionDataAdapter adapter = new ExecutionDataAdapter(1L, "com/example/Foo", jacocoProbes);

    boolean[] probeActivations = adapter.getProbeActivations();
    assertEquals(4, probeActivations.length);
    assertArrayEquals(new boolean[4], probeActivations);
  }

  @Test
  void mergeIntoJacocoProbesOrsPerTestBitsBack() {
    boolean[] jacocoProbes = new boolean[4];
    // a bit that was already set on the shared array (e.g. covered outside any test)
    jacocoProbes[0] = true;
    ExecutionDataAdapter adapter = new ExecutionDataAdapter(1L, "com/example/Foo", jacocoProbes);

    // simulate Jacoco's native probes writing into the per-test array
    adapter.getProbeActivations()[2] = true;

    adapter.mergeIntoJacocoProbes();

    // existing shared bit is preserved, per-test bit is folded back, untouched probes stay false
    assertArrayEquals(new boolean[] {true, false, true, false}, jacocoProbes);
  }

  @Test
  void mergeIntoJacocoProbesNeverClearsBits() {
    boolean[] jacocoProbes = new boolean[] {true, true, true, true};
    ExecutionDataAdapter adapter = new ExecutionDataAdapter(1L, "com/example/Foo", jacocoProbes);
    // per-test array is all false; merging it back must not clear any shared bits
    adapter.mergeIntoJacocoProbes();
    assertArrayEquals(new boolean[] {true, true, true, true}, jacocoProbes);
  }

  @Test
  void mergeCombinesProbeActivationsFromAnotherAdapter() {
    boolean[] jacocoProbes = new boolean[4];
    ExecutionDataAdapter a = new ExecutionDataAdapter(1L, "com/example/Foo", jacocoProbes);
    ExecutionDataAdapter b = new ExecutionDataAdapter(1L, "com/example/Foo", jacocoProbes);
    a.getProbeActivations()[1] = true;
    b.getProbeActivations()[3] = true;

    ExecutionDataAdapter merged = a.merge(b);

    assertTrue(merged.getProbeActivations()[1]);
    assertTrue(merged.getProbeActivations()[3]);
    assertFalse(merged.getProbeActivations()[0]);
  }

  @Test
  void toExecutionDataExposesPerTestProbes() {
    boolean[] jacocoProbes = new boolean[3];
    ExecutionDataAdapter adapter = new ExecutionDataAdapter(7L, "com/example/Foo", jacocoProbes);
    adapter.getProbeActivations()[1] = true;

    assertEquals(7L, adapter.toExecutionData().getId());
    assertArrayEquals(new boolean[] {false, true, false}, adapter.toExecutionData().getProbes());
  }
}
