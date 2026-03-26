package datadog.trace.codecoverage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.ExecutionDataStore;
import org.junit.jupiter.api.Test;

class CodeCoverageTransformerTest {

  @Test
  void snapshotExecutionData_clonesHitProbeArrays() {
    ExecutionDataStore target = new ExecutionDataStore();
    boolean[] probes = new boolean[] {true, false, true};

    CodeCoverageTransformer.snapshotExecutionData(target)
        .visitClassExecution(new ExecutionData(123L, "example/Foo", probes));

    // Emulate the reset that RuntimeData.collect(..., true) performs on the live array.
    probes[0] = false;
    probes[2] = false;

    ExecutionData snapshot = target.get(123L);
    assertNotNull(snapshot);
    assertNotSame(probes, snapshot.getProbes());
    assertArrayEquals(new boolean[] {true, false, true}, snapshot.getProbes());
  }

  @Test
  void snapshotExecutionData_skipsClassesWithoutHits() {
    ExecutionDataStore target = new ExecutionDataStore();

    CodeCoverageTransformer.snapshotExecutionData(target)
        .visitClassExecution(new ExecutionData(123L, "example/Foo", new boolean[3]));

    assertNull(target.get(123L));
  }
}
