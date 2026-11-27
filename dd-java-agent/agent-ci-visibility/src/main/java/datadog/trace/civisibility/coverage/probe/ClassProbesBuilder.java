package datadog.trace.civisibility.coverage.probe;

import datadog.trace.api.civisibility.coverage.InstrumentedClass;
import datadog.trace.api.civisibility.coverage.SourceMap;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
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

  private ClassProbesBuilder(int initStart) {
    this.initStart = initStart;
  }

  /** Writes the probes for {@link #readFrom} to read, in another JVM too. */
  void writeTo(DataOutputStream out) throws IOException {
    out.writeInt(initStart);
    writeTree(out, probeLines, probeParent);
    writeTree(out, nodeLines, nodeParent);
  }

  private static void writeTree(DataOutputStream out, List<int[]> lines, int[] parents)
      throws IOException {
    out.writeInt(lines.size());
    for (int id = 0; id < lines.size(); id++) {
      out.writeInt(parents[id]);
      int[] idLines = lines.get(id);
      out.writeInt(idLines.length);
      for (int line : idLines) {
        out.writeInt(line);
      }
    }
  }

  /** Reads the probes that {@link #writeTo} wrote. */
  static ClassProbesBuilder readFrom(DataInputStream in) throws IOException {
    ClassProbesBuilder probes = new ClassProbesBuilder(in.readInt());
    int probeCount = readCount(in);
    for (int id = 0; id < probeCount; id++) {
      int parent = in.readInt();
      probes.newProbe(readLines(in), parent);
    }
    int nodeCount = readCount(in);
    for (int id = 0; id < nodeCount; id++) {
      int parent = in.readInt();
      probes.newNode(readLines(in), parent);
    }
    return probes;
  }

  private static int[] readLines(DataInputStream in) throws IOException {
    int[] lines = new int[readCount(in)];
    for (int k = 0; k < lines.length; k++) {
      lines[k] = in.readInt();
    }
    return lines;
  }

  /** A count of ints that follow, which cannot be more than the ints left to read. */
  private static int readCount(DataInputStream in) throws IOException {
    int count = in.readInt();
    if (count < 0 || count > in.available() / 4) {
      throw new IOException("count out of range: " + count);
    }
    return count;
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
