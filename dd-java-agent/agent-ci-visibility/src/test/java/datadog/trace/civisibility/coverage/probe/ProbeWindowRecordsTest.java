package datadog.trace.civisibility.coverage.probe;

import static datadog.trace.civisibility.coverage.probe.ConcurrentWorkloads.FIXTURES;
import static datadog.trace.civisibility.coverage.probe.ConcurrentWorkloads.referenceLinesOfEachWorker;
import static datadog.trace.civisibility.coverage.probe.ConcurrentWorkloads.run;
import static datadog.trace.civisibility.coverage.probe.TestWindow.sharedLines;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.civisibility.coverage.CoverageWindow;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * A window keeps one record of hits per class, however often the thread moves hits of the class to
 * it, and the lines of the records are those of all the moves together.
 */
@ExtendWith(NoDroppedEntries.class)
class ProbeWindowRecordsTest {

  private static final int MOVES = 50;

  private static List<Map<String, BitSet>> workerLines;

  @BeforeAll
  static void setUp() {
    workerLines = referenceLinesOfEachWorker();
  }

  @Test
  void movesOfTheSameClassesMergeIntoOneRecordPerClass() {
    FixtureLoader loader = new FixtureLoader(null, FIXTURES);
    TestWindow window = new TestWindow();
    for (int move = 0; move < MOVES; move++) {
      // two workers run different methods of the same classes
      run(loader, move % 2);
      // opening a window on the thread moves what it hit to the outer window
      assertTrue(new TestWindow().closeAndCollect().isEmpty());
    }
    List<CoverageWindow.ClassHits> records = window.closeAndTakeRecords();

    Set<Integer> classIds = new HashSet<>();
    for (CoverageWindow.ClassHits record : records) {
      classIds.add(record.classId);
    }
    assertEquals(classIds.size(), records.size(), "records per class");
    assertEquals(union(workerLines.get(0), workerLines.get(1)), TestWindow.lines(records));
    assertTrue(sharedLines(loader).isEmpty());
  }

  private static Map<String, BitSet> union(Map<String, BitSet> first, Map<String, BitSet> second) {
    Map<String, BitSet> union = new HashMap<>();
    addAll(union, first);
    addAll(union, second);
    return union;
  }

  private static void addAll(Map<String, BitSet> union, Map<String, BitSet> lines) {
    for (Map.Entry<String, BitSet> entry : lines.entrySet()) {
      union.computeIfAbsent(entry.getKey(), path -> new BitSet()).or(entry.getValue());
    }
  }
}
