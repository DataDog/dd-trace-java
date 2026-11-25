package datadog.trace.api.civisibility.coverage;

import java.lang.ref.WeakReference;
import java.util.BitSet;
import javax.annotation.Nullable;

/**
 * Describes the probes of one instrumented class and the source lines each probe proves executed.
 *
 * <p>A probe proves its own lines plus the lines of every basic block that dominates it. Dominating
 * blocks are stored once as nodes of a tree: {@code probeParent[p]} is the node of the block that
 * immediately dominates probe {@code p}, and {@code nodeParent[n]} continues the chain up to -1.
 *
 * <p>Probes of class initializers occupy the ids from {@link #initStart()} up to the probe count.
 * They are never reset: their lines are the class's init lines.
 */
public final class InstrumentedClass {

  private final String name;
  private final WeakReference<ClassLoader> loader;
  @Nullable private final String sourceFile;
  @Nullable private final SourceMap sourceMap;
  private final int initStart;
  private final int[][] probeLines;
  private final int[] probeParent;
  private final int[][] nodeLines;
  private final int[] nodeParent;

  private volatile Object resolved;

  public InstrumentedClass(
      String name,
      ClassLoader loader,
      @Nullable String sourceFile,
      @Nullable SourceMap sourceMap,
      int initStart,
      int[][] probeLines,
      int[] probeParent,
      int[][] nodeLines,
      int[] nodeParent) {
    this.name = name;
    this.loader = new WeakReference<>(loader);
    this.sourceFile = sourceFile;
    this.sourceMap = sourceMap;
    this.initStart = initStart;
    this.probeLines = probeLines;
    this.probeParent = probeParent;
    this.nodeLines = nodeLines;
    this.nodeParent = nodeParent;
  }

  /** Internal name, such as {@code com/example/Foo}. */
  public String name() {
    return name;
  }

  @Nullable
  public ClassLoader loader() {
    return loader.get();
  }

  @Nullable
  public String sourceFile() {
    return sourceFile;
  }

  /**
   * The source map of the class, if it has one. Line numbers in this class are the class's own;
   * without a source map they are lines of {@link #sourceFile()}.
   */
  @Nullable
  public SourceMap sourceMap() {
    return sourceMap;
  }

  public int probeCount() {
    return probeLines.length;
  }

  public int initStart() {
    return initStart;
  }

  /** Adds the lines proven by the probes set in {@code probes} to {@code lines}. */
  public void addLines(long[] probes, BitSet lines) {
    boolean[] visitedNodes = null;
    for (int word = 0; word < probes.length; word++) {
      long bits = probes[word];
      while (bits != 0) {
        int probe = word * 64 + Long.numberOfTrailingZeros(bits);
        bits &= bits - 1;
        visitedNodes = addProbeLines(probe, lines, visitedNodes);
      }
    }
  }

  /** Adds the lines of the class initializer probes that are set in {@code probes}. */
  public void addInitLines(boolean[] probes, BitSet lines) {
    boolean[] visitedNodes = null;
    for (int probe = initStart; probe < probes.length; probe++) {
      if (probes[probe]) {
        visitedNodes = addProbeLines(probe, lines, visitedNodes);
      }
    }
  }

  /** Adds the lines of the probes set in {@code probes}, other than class initializer probes. */
  public void addMethodLines(boolean[] probes, BitSet lines) {
    boolean[] visitedNodes = null;
    for (int probe = 1; probe < initStart; probe++) {
      if (probes[probe]) {
        visitedNodes = addProbeLines(probe, lines, visitedNodes);
      }
    }
  }

  private boolean[] addProbeLines(int probe, BitSet lines, @Nullable boolean[] visitedNodes) {
    for (int line : probeLines[probe]) {
      lines.set(line);
    }
    for (int node = probeParent[probe]; node >= 0; node = nodeParent[node]) {
      if (visitedNodes == null) {
        visitedNodes = new boolean[nodeLines.length];
      } else if (visitedNodes[node]) {
        break;
      }
      visitedNodes[node] = true;
      for (int line : nodeLines[node]) {
        lines.set(line);
      }
    }
    return visitedNodes;
  }

  /** Caches what the reporting side resolved for this class, such as its source path. */
  @Nullable
  public Object getResolved() {
    return resolved;
  }

  public void setResolved(Object resolved) {
    this.resolved = resolved;
  }
}
