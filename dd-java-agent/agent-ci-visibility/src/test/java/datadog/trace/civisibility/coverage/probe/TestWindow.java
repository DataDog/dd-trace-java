package datadog.trace.civisibility.coverage.probe;

import datadog.trace.api.DDTraceId;
import datadog.trace.api.civisibility.coverage.CoverageProbes;
import datadog.trace.api.civisibility.coverage.CoverageStore;
import datadog.trace.api.civisibility.coverage.CoverageWindow;
import datadog.trace.api.civisibility.coverage.InstrumentedClass;
import datadog.trace.api.civisibility.coverage.NoOpProbes;
import datadog.trace.api.civisibility.coverage.ProbeRecorder;
import datadog.trace.api.civisibility.coverage.SharedPart;
import datadog.trace.api.civisibility.coverage.TestReport;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;

/**
 * A coverage window that tests open and close by hand, and the lines that windows collect. It is
 * also the coverage store of a test context that tests make by hand.
 */
final class TestWindow extends CoverageWindow implements CoverageStore {

  TestWindow() {}

  TestWindow(@Nullable CoverageWindow replaced) {
    super(replaced);
  }

  @Override
  public CoverageProbes getProbes() {
    return NoOpProbes.INSTANCE;
  }

  @Override
  public boolean report(DDTraceId testSessionId, Long testSuiteId, long testSpanId) {
    return false;
  }

  @Nullable
  @Override
  public TestReport getReport() {
    return null;
  }

  /** Closes the window: the lines it collected, by source path. */
  Map<String, BitSet> closeAndCollect() {
    return lines(close());
  }

  static Map<String, BitSet> lines(List<CoverageWindow.ClassHits> classHits) {
    Map<String, BitSet> lines = new HashMap<>();
    for (CoverageWindow.ClassHits hits : classHits) {
      BitSet classLines = new BitSet();
      hits.instrumentedClass.addLines(hits.probes, classLines);
      lines
          .computeIfAbsent(sourcePath(hits.instrumentedClass), path -> new BitSet())
          .or(classLines);
    }
    return lines;
  }

  /** The unowned Shared lines of the classes a loader defined, by source path. */
  static Map<String, BitSet> sharedLines(ClassLoader loader) {
    return sharedLines(loader, SharedPart.UNOWNED);
  }

  /** The lines in a part of Shared of the classes a loader defined, by source path. */
  static Map<String, BitSet> sharedLines(ClassLoader loader, SharedPart part) {
    ProbeRecorder.flushShared();
    Map<String, BitSet> lines = new HashMap<>();
    for (int classId = 0; classId < ProbeRecorder.getClassCount(); classId++) {
      InstrumentedClass instrumentedClass = ProbeRecorder.getInstrumentedClass(classId);
      if (instrumentedClass != null && instrumentedClass.loader() == loader) {
        BitSet classLines = new BitSet();
        if (ProbeRecorder.addSharedLines(part, classId, classLines)) {
          lines.computeIfAbsent(sourcePath(instrumentedClass), path -> new BitSet()).or(classLines);
        }
      }
    }
    return lines;
  }

  @SafeVarargs
  static Map<String, BitSet> union(Map<String, BitSet>... parts) {
    return union(Arrays.asList(parts));
  }

  static Map<String, BitSet> union(List<Map<String, BitSet>> parts) {
    Map<String, BitSet> union = new HashMap<>();
    for (Map<String, BitSet> lines : parts) {
      for (Map.Entry<String, BitSet> entry : lines.entrySet()) {
        union.computeIfAbsent(entry.getKey(), path -> new BitSet()).or(entry.getValue());
      }
    }
    return union;
  }

  private static String sourcePath(InstrumentedClass instrumentedClass) {
    return ReferenceLines.sourcePath(instrumentedClass.name(), instrumentedClass.sourceFile());
  }
}
