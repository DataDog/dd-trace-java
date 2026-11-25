package datadog.trace.civisibility.coverage.probe;

import datadog.trace.api.civisibility.coverage.InstrumentedClass;
import datadog.trace.api.civisibility.coverage.SourceMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.annotation.Nullable;

/** Allocates the probe ids of one class and collects the lines each probe proves. */
final class ClassProbesBuilder {

  private static final int[] NO_LINES = new int[0];

  private final List<int[]> probeLines = new ArrayList<>();
  private int[] probeParent = new int[16];
  private final List<int[]> nodeLines = new ArrayList<>();
  private int[] nodeParent = new int[16];
  private int initStart = -1;

  ClassProbesBuilder() {
    // id 0 is where exception handlers store when the method started no code that can throw
    newProbe(NO_LINES, -1);
  }

  int newProbe(int[] lines, int parentNode) {
    int id = probeLines.size();
    probeLines.add(lines);
    if (id == probeParent.length) {
      probeParent = Arrays.copyOf(probeParent, id * 2);
    }
    probeParent[id] = parentNode;
    return id;
  }

  int newNode(int[] lines, int parentNode) {
    int id = nodeLines.size();
    nodeLines.add(lines);
    if (id == nodeParent.length) {
      nodeParent = Arrays.copyOf(nodeParent, id * 2);
    }
    nodeParent[id] = parentNode;
    return id;
  }

  int probeCount() {
    return probeLines.size();
  }

  /** Probes allocated from now on belong to the class initializer. */
  void startInitializerProbes() {
    initStart = probeLines.size();
  }

  InstrumentedClass build(
      String className,
      ClassLoader loader,
      @Nullable String sourceFile,
      @Nullable SourceMap sourceMap) {
    int probeCount = probeLines.size();
    return new InstrumentedClass(
        className,
        loader,
        sourceFile,
        sourceMap,
        initStart >= 0 ? initStart : probeCount,
        probeLines.toArray(new int[0][]),
        Arrays.copyOf(probeParent, probeCount),
        nodeLines.toArray(new int[0][]),
        Arrays.copyOf(nodeParent, nodeLines.size()));
  }
}
